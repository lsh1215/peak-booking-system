package com.peakbooking.booking.application;

import com.peakbooking.booking.config.BookingProperties;
import com.peakbooking.booking.domain.AdmissionDecision;
import com.peakbooking.booking.domain.GateMode;
import com.peakbooking.booking.infrastructure.jpa.BookingJpaRepository;
import com.peakbooking.booking.redis.RedisAdmissionGateway;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Semaphore;
import org.springframework.beans.factory.annotation.Value;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.RedisSystemException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.CannotCreateTransactionException;

@Service
public class BookingAdmissionService {

    private static final Logger log = LoggerFactory.getLogger(BookingAdmissionService.class);
    private static final int REDIS_ADMISSION_PERSISTENCE_ATTEMPTS = 3;
    private static final Duration REDIS_ADMISSION_PERSISTENCE_BACKOFF = Duration.ofMillis(20);

    private final BookingProperties properties;
    private final BookingJpaRepository repository;
    private final RedisAdmissionGateway redisAdmissionGateway;
    private final AdmissionTransactionService transactionService;
    private final BookingDbWriteBulkhead dbWriteBulkhead;
    private final Semaphore dbFallbackBulkhead;
    private final Semaphore redisFailureDiagnosis = new Semaphore(1);
    private final Duration gateModeCacheTtl;
    private final ConcurrentMap<String, CachedGateMode> gateModeCache = new ConcurrentHashMap<>();

    public BookingAdmissionService(
            BookingProperties properties,
            BookingJpaRepository repository,
            RedisAdmissionGateway redisAdmissionGateway,
            AdmissionTransactionService transactionService,
            BookingDbWriteBulkhead dbWriteBulkhead,
            @Value("${peak-booking.gate-mode-cache-ttl:1s}") Duration gateModeCacheTtl
    ) {
        this.properties = properties;
        this.repository = repository;
        this.redisAdmissionGateway = redisAdmissionGateway;
        this.transactionService = transactionService;
        this.dbWriteBulkhead = dbWriteBulkhead;
        this.dbFallbackBulkhead = new Semaphore(properties.bulkhead().dbFallbackConcurrency());
        this.gateModeCacheTtl = gateModeCacheTtl;
    }

    public AdmissionDecision admit(long saleEventId, long productId, long userId, String bookingAttemptId) {
        GateMode currentMode = cachedGateMode(saleEventId, productId);
        if (currentMode == GateMode.DB_FALLBACK) {
            return dbFallbackAdmission(saleEventId, productId, userId, bookingAttemptId, false);
        }

        try {
            return redisAdmissionWithDurablePersistence(saleEventId, productId, userId, bookingAttemptId);
        } catch (RedisSystemException
                 | QueryTimeoutException
                 | DataAccessResourceFailureException redisFailure) {
            if (!redisFailureDiagnosis.tryAcquire()) {
                if (cachedGateMode(saleEventId, productId) == GateMode.DB_FALLBACK) {
                    return dbFallbackAdmission(saleEventId, productId, userId, bookingAttemptId, false);
                }
                return AdmissionDecision.rejected(GateMode.REDIS);
            }
            try {
                if (cachedGateMode(saleEventId, productId) == GateMode.DB_FALLBACK) {
                    return dbFallbackAdmission(saleEventId, productId, userId, bookingAttemptId, false);
                }
                try {
                    return redisAdmissionWithDurablePersistence(saleEventId, productId, userId, bookingAttemptId);
                } catch (RedisSystemException
                         | QueryTimeoutException
                         | DataAccessResourceFailureException retryFailure) {
                    redisFailure.addSuppressed(retryFailure);
                    if ((isRedisCommandTimeout(redisFailure) || isRedisCommandTimeout(retryFailure))
                            && redisStillHealthy()) {
                        log.debug(
                                "Redis admission timed out after retry while Redis health is still OK; rejecting saleEventId={} productId={} without DB fallback",
                                saleEventId,
                                productId,
                                redisFailure
                        );
                        return AdmissionDecision.rejected(GateMode.REDIS);
                    }
                    return switchToDbFallback(saleEventId, productId, userId, bookingAttemptId, redisFailure);
                }
            } finally {
                redisFailureDiagnosis.release();
            }
        }
    }

    private AdmissionDecision redisAdmissionWithDurablePersistence(
            long saleEventId,
            long productId,
            long userId,
            String bookingAttemptId
    ) {
        return dbWriteBulkhead.execute(() -> {
            RedisAdmissionGateway.Result result = tryRedisAdmission(saleEventId, productId, userId);
            if (!result.admitted()) {
                return AdmissionDecision.rejected(GateMode.REDIS);
            }
            return persistRedisAdmission(
                    saleEventId,
                    productId,
                    userId,
                    bookingAttemptId,
                    GateMode.REDIS,
                    result.redisSeq(),
                    properties.candidateLimit()
            );
        });
    }

    private AdmissionDecision persistRedisAdmission(
            long saleEventId,
            long productId,
            long userId,
            String bookingAttemptId,
            GateMode gateMode,
            long redisSeq,
            int candidateLimit
    ) {
        RuntimeException lastFailure = null;
        for (int attempt = 1; attempt <= REDIS_ADMISSION_PERSISTENCE_ATTEMPTS; attempt++) {
            try {
                return transactionService.createAdmission(
                        saleEventId,
                        productId,
                        userId,
                        bookingAttemptId,
                        gateMode,
                        redisSeq,
                        candidateLimit
                );
            } catch (CannotCreateTransactionException
                     | QueryTimeoutException
                     | DataAccessResourceFailureException transientDbFailure) {
                lastFailure = transientDbFailure;
                if (attempt == REDIS_ADMISSION_PERSISTENCE_ATTEMPTS) {
                    break;
                }
                sleepBeforeAdmissionRetry(attempt);
            }
        }
        throw lastFailure;
    }

    private void sleepBeforeAdmissionRetry(int attempt) {
        try {
            Thread.sleep(REDIS_ADMISSION_PERSISTENCE_BACKOFF.multipliedBy(attempt).toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while persisting Redis admission", e);
        }
    }

    private RedisAdmissionGateway.Result tryRedisAdmission(long saleEventId, long productId, long userId) {
        return redisAdmissionGateway.tryAdmit(
                productId,
                saleEventId,
                userId,
                properties.candidateLimit()
        );
    }

    private AdmissionDecision switchToDbFallback(
            long saleEventId,
            long productId,
            long userId,
            String bookingAttemptId,
            RuntimeException redisFailure
    ) {
        if (rememberGateMode(saleEventId, productId, GateMode.DB_FALLBACK)) {
            log.warn(
                    "Redis admission unavailable; switching saleEventId={} productId={} to bounded DB fallback",
                    saleEventId,
                    productId,
                    redisFailure
            );
        }
        return dbFallbackAdmission(saleEventId, productId, userId, bookingAttemptId, true);
    }

    private boolean isRedisCommandTimeout(Throwable failure) {
        Throwable current = failure;
        while (current != null) {
            if (current instanceof QueryTimeoutException
                    || "io.lettuce.core.RedisCommandTimeoutException".equals(current.getClass().getName())) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private boolean redisStillHealthy() {
        try {
            return redisAdmissionGateway.ping();
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private AdmissionDecision dbFallbackAdmission(
            long saleEventId,
            long productId,
            long userId,
            String bookingAttemptId,
            boolean markFallback
    ) {
        if (!dbFallbackBulkhead.tryAcquire()) {
            return AdmissionDecision.rejected(GateMode.DB_FALLBACK);
        }
        try {
            if (markFallback) {
                AdmissionDecision decision = dbWriteBulkhead.execute(() -> transactionService.markFallbackAndCreate(
                        saleEventId,
                        productId,
                        userId,
                        bookingAttemptId,
                        properties.candidateLimit()
                ));
                rememberGateMode(saleEventId, productId, GateMode.DB_FALLBACK);
                return decision;
            }
            return dbWriteBulkhead.execute(() -> transactionService.createAdmission(
                    saleEventId,
                    productId,
                    userId,
                    bookingAttemptId,
                    GateMode.DB_FALLBACK,
                    null,
                    properties.candidateLimit()
            ));
        } finally {
            dbFallbackBulkhead.release();
        }
    }

    private GateMode cachedGateMode(long saleEventId, long productId) {
        if (gateModeCacheTtl.isZero() || gateModeCacheTtl.isNegative()) {
            return repository.gateMode(saleEventId, productId);
        }
        String key = cacheKey(saleEventId, productId);
        long now = System.nanoTime();
        CachedGateMode cached = gateModeCache.get(key);
        if (cached != null && cached.expiresAtNanos() > now) {
            return cached.mode();
        }
        return gateModeCache.compute(key, (ignored, existing) -> {
            long current = System.nanoTime();
            if (existing != null && existing.expiresAtNanos() > current) {
                return existing;
            }
            return new CachedGateMode(
                    repository.gateMode(saleEventId, productId),
                    current + gateModeCacheTtl.toNanos()
            );
        }).mode();
    }

    private boolean rememberGateMode(long saleEventId, long productId, GateMode mode) {
        CachedGateMode previous = gateModeCache.put(
                cacheKey(saleEventId, productId),
                new CachedGateMode(mode, Long.MAX_VALUE)
        );
        return previous == null || previous.mode() != mode;
    }

    private String cacheKey(long saleEventId, long productId) {
        return saleEventId + ":" + productId;
    }

    private record CachedGateMode(GateMode mode, long expiresAtNanos) {
    }
}
