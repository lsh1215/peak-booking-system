package com.peakbooking.booking.application.admission;

import com.peakbooking.booking.application.localqueue.LocalWaitingRoom;
import com.peakbooking.booking.application.support.BookingDbWriteBulkhead;
import com.peakbooking.booking.config.BookingProperties;
import com.peakbooking.booking.domain.AdmissionDecision;
import com.peakbooking.booking.domain.BookingErrorCode;
import com.peakbooking.booking.domain.GateMode;
import com.peakbooking.booking.infrastructure.jpa.BookingJpaRepository;
import com.peakbooking.booking.redis.RedisAdmissionGateway;
import com.peakbooking.common.exception.BusinessException;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Semaphore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
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
    private static final String ADMISSION_PERSISTENCE_UNAVAILABLE = "ADMISSION_PERSISTENCE_UNAVAILABLE";

    private final BookingProperties properties;
    private final BookingJpaRepository repository;
    private final RedisAdmissionGateway redisAdmissionGateway;
    private final AdmissionTransactionService transactionService;
    private final BookingDbWriteBulkhead dbWriteBulkhead;
    private final LocalWaitingRoom localWaitingRoom;
    private final Semaphore redisAdmissionPermits;
    private final Semaphore redisRecoveryProbePermit = new Semaphore(1);
    private final Duration gateModeCacheTtl;
    private final ConcurrentMap<String, CachedGateMode> gateModeCache = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Long> redisPauseUntilNanos = new ConcurrentHashMap<>();

    @Autowired
    public BookingAdmissionService(
            BookingProperties properties,
            BookingJpaRepository repository,
            RedisAdmissionGateway redisAdmissionGateway,
            AdmissionTransactionService transactionService,
            BookingDbWriteBulkhead dbWriteBulkhead,
            LocalWaitingRoom localWaitingRoom,
            @Value("${peak-booking.gate-mode-cache-ttl:1s}") Duration gateModeCacheTtl
    ) {
        this.properties = properties;
        this.repository = repository;
        this.redisAdmissionGateway = redisAdmissionGateway;
        this.transactionService = transactionService;
        this.dbWriteBulkhead = dbWriteBulkhead;
        this.localWaitingRoom = localWaitingRoom;
        this.redisAdmissionPermits = new Semaphore(Math.max(1, properties.bulkhead().redisAdmissionConcurrency()));
        this.gateModeCacheTtl = gateModeCacheTtl;
    }

    public BookingAdmissionService(
            BookingProperties properties,
            BookingJpaRepository repository,
            RedisAdmissionGateway redisAdmissionGateway,
            AdmissionTransactionService transactionService,
            BookingDbWriteBulkhead dbWriteBulkhead,
            Duration gateModeCacheTtl
    ) {
        this(
                properties,
                repository,
                redisAdmissionGateway,
                transactionService,
                dbWriteBulkhead,
                null,
                gateModeCacheTtl
        );
    }

    public AdmissionDecision admit(long saleEventId, long productId, long userId, String bookingAttemptId) {
        if (isLocallyPaused(saleEventId, productId)) {
            return AdmissionDecision.rejected(GateMode.REDIS_FAILOVER_PAUSED);
        }

        GateMode currentMode = cachedGateMode(saleEventId, productId);
        if (currentMode != GateMode.REDIS) {
            if (currentMode != GateMode.REDIS_FAILOVER_PAUSED
                    || !tryRecoverRedisGate(saleEventId, productId)) {
                return AdmissionDecision.rejected(currentMode);
            }
        }

        if (!redisAdmissionPermits.tryAcquire()) {
            rememberGateMode(saleEventId, productId, GateMode.REDIS_FAILOVER_PAUSED);
            log.warn(
                    "Redis admission probes are saturated; pausing saleEventId={} productId={} without entering Redis",
                    saleEventId,
                    productId
            );
            return AdmissionDecision.rejected(GateMode.REDIS_FAILOVER_PAUSED);
        }
        try {
            return redisAdmissionWithDurablePersistence(saleEventId, productId, userId, bookingAttemptId);
        } catch (RedisAdmissionFailureException redisFailure) {
            return pauseForRedisFailover(saleEventId, productId, userId, redisFailure);
        } catch (AdmissionPersistenceUnavailableException persistenceFailure) {
            log.warn(
                    "MySQL admission persistence unavailable after Redis admission; rejecting saleEventId={} productId={} userId={}",
                    saleEventId,
                    productId,
                    userId,
                    persistenceFailure.cause()
            );
            return AdmissionDecision.rejected(GateMode.REDIS_FAILOVER_PAUSED);
        } finally {
            redisAdmissionPermits.release();
        }
    }

    public void releaseActiveCandidate(
            long saleEventId,
            long productId,
            long userId,
            Long redisSeq,
            String reason
    ) {
        if (redisSeq == null || redisSeq <= 0) {
            return;
        }
        try {
            boolean released = redisAdmissionGateway.releaseAdmission(productId, saleEventId, userId, redisSeq);
            if (!released) {
                log.debug(
                        "Redis active candidate release skipped saleEventId={} productId={} userId={} redisSeq={} reason={}",
                        saleEventId,
                        productId,
                        userId,
                        redisSeq,
                        reason
                );
            }
        } catch (RuntimeException failure) {
            log.warn(
                    "Redis active candidate release failed saleEventId={} productId={} userId={} redisSeq={} reason={}",
                    saleEventId,
                    productId,
                    userId,
                    redisSeq,
                    reason,
                    failure
            );
        }
    }

    public AdmissionDecision admitFromLocalQueue(
            long saleEventId,
            long productId,
            long userId,
            String bookingAttemptId
    ) {
        return dbWriteBulkhead.execute(() -> transactionService.createLocalQueueAdmission(
                saleEventId,
                productId,
                userId,
                bookingAttemptId,
                properties.candidateLimit()
        ));
    }

    private AdmissionDecision redisAdmissionWithDurablePersistence(
            long saleEventId,
            long productId,
            long userId,
            String bookingAttemptId
    ) {
        // Redis can grant a fast candidate slot, but MySQL is still the durable
        // admission ledger. If DB persistence fails, pause admission and compensate
        // only the newly created Redis candidate instead of flushing the whole pool.
        return dbWriteBulkhead.execute(() -> {
            if (isCrossReplicaAdmissionPaused(saleEventId, productId)) {
                return AdmissionDecision.rejected(GateMode.REDIS_FAILOVER_PAUSED);
            }
            RedisAdmissionGateway.Result result = tryRedisAdmission(saleEventId, productId, userId);
            if (result.waitingRoom()) {
                return AdmissionDecision.waitingRoom(result.redisSeq(), result.candidateRank(), GateMode.REDIS);
            }
            if (!result.admitted()) {
                return AdmissionDecision.rejected(GateMode.REDIS);
            }
            try {
                return persistRedisAdmission(
                        saleEventId,
                        productId,
                        userId,
                        bookingAttemptId,
                        GateMode.REDIS,
                        result.redisSeq(),
                        properties.candidateLimit()
                );
            } catch (CannotCreateTransactionException
                     | QueryTimeoutException
                     | DataAccessResourceFailureException persistenceFailure) {
                openAdmissionPersistencePause(saleEventId, productId, persistenceFailure);
                compensateRedisAdmissionIfNewlyCreated(saleEventId, productId, userId, result);
                throw new AdmissionPersistenceUnavailableException(persistenceFailure);
            }
        });
    }

    private boolean isCrossReplicaAdmissionPaused(long saleEventId, long productId) {
        try {
            boolean paused = redisAdmissionGateway.isAdmissionPaused(productId, saleEventId);
            if (paused) {
                rememberGateMode(saleEventId, productId, GateMode.REDIS_FAILOVER_PAUSED);
            }
            return paused;
        } catch (RedisSystemException | QueryTimeoutException | DataAccessResourceFailureException failure) {
            throw new RedisAdmissionFailureException(failure);
        }
    }

    private void openAdmissionPersistencePause(
            long saleEventId,
            long productId,
            RuntimeException persistenceFailure
    ) {
        rememberGateMode(saleEventId, productId, GateMode.REDIS_FAILOVER_PAUSED);
        try {
            redisAdmissionGateway.pauseAdmission(
                    productId,
                    saleEventId,
                    properties.redisFailoverRetryAfter(),
                    ADMISSION_PERSISTENCE_UNAVAILABLE
            );
        } catch (RuntimeException pauseFailure) {
            log.warn(
                    "Redis admission persistence pause marker could not be written; local pause remains active saleEventId={} productId={}",
                    saleEventId,
                    productId,
                    pauseFailure
            );
        }
        log.warn(
                "Redis admission paused because MySQL official admission persistence failed saleEventId={} productId={}",
                saleEventId,
                productId,
                persistenceFailure
        );
    }

    private void compensateRedisAdmissionIfNewlyCreated(
            long saleEventId,
            long productId,
            long userId,
            RedisAdmissionGateway.Result result
    ) {
        if (!result.newlyCreated()) {
            return;
        }
        try {
            boolean compensated = redisAdmissionGateway.compensateAdmission(
                    productId,
                    saleEventId,
                    userId,
                    result.redisSeq()
            );
            if (!compensated) {
                log.warn(
                        "Redis admission compensation skipped because candidate changed saleEventId={} productId={} userId={} redisSeq={}",
                        saleEventId,
                        productId,
                        userId,
                        result.redisSeq()
                );
            }
        } catch (RuntimeException compensationFailure) {
            log.warn(
                    "Redis admission compensation failed; admission remains paused saleEventId={} productId={} userId={} redisSeq={}",
                    saleEventId,
                    productId,
                    userId,
                    result.redisSeq(),
                    compensationFailure
            );
        }
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
        try {
            return redisAdmissionGateway.tryAdmit(
                    productId,
                    saleEventId,
                    userId,
                    properties.candidateLimit()
            );
        } catch (RedisSystemException | QueryTimeoutException | DataAccessResourceFailureException failure) {
            throw new RedisAdmissionFailureException(failure);
        }
    }

    private AdmissionDecision pauseForRedisFailover(
            long saleEventId,
            long productId,
            long userId,
            RedisAdmissionFailureException redisFailure
    ) {
        compensateReplicationUnconfirmedCandidateIfNeeded(saleEventId, productId, userId, redisFailure);
        boolean firstLocalPause = rememberGateMode(saleEventId, productId, GateMode.REDIS_FAILOVER_PAUSED);
        boolean persisted = true;
        try {
            dbWriteBulkhead.execute(() -> {
                transactionService.markRedisFailoverPaused(saleEventId, productId);
                return null;
            });
        } catch (BusinessException markerFailure) {
            if (markerFailure.getErrorCode() != BookingErrorCode.SERVICE_BUSY) {
                throw markerFailure;
            }
            persisted = false;
            log.warn(
                    "Redis failover pause marker is busy; failing closed with local pause saleEventId={} productId={}",
                    saleEventId,
                    productId,
                    markerFailure
            );
        } catch (CannotCreateTransactionException
                 | QueryTimeoutException
                 | DataAccessResourceFailureException markerFailure) {
            persisted = false;
            log.warn(
                    "Redis failover pause marker could not be persisted; failing closed with local pause saleEventId={} productId={}",
                    saleEventId,
                    productId,
                    markerFailure
                );
        }
        if (firstLocalPause) {
            log.warn(
                    "Redis admission unavailable; pausing saleEventId={} productId={} admission until Redis failover finishes persisted={}",
                    saleEventId,
                    productId,
                    persisted,
                    redisFailure
            );
        }
        return AdmissionDecision.rejected(GateMode.REDIS_FAILOVER_PAUSED);
    }

    private void compensateReplicationUnconfirmedCandidateIfNeeded(
            long saleEventId,
            long productId,
            long userId,
            RedisAdmissionFailureException redisFailure
    ) {
        if (!(redisFailure.cause() instanceof RedisAdmissionGateway.ReplicationNotConfirmedException replicationFailure)
                || !replicationFailure.hasCandidate()) {
            return;
        }
        if (replicationFailure.saleEventId() != saleEventId
                || replicationFailure.productId() != productId
                || replicationFailure.userId() != userId) {
            log.warn(
                    "Redis replication failure candidate metadata did not match request saleEventId={} productId={} userId={} redisSeq={}",
                    saleEventId,
                    productId,
                    userId,
                    replicationFailure.redisSeq()
            );
            return;
        }
        try {
            boolean compensated = redisAdmissionGateway.compensateAdmission(
                    productId,
                    saleEventId,
                    userId,
                    replicationFailure.redisSeq()
            );
            if (!compensated) {
                log.warn(
                        "Redis replication failure compensation skipped because candidate changed saleEventId={} productId={} userId={} redisSeq={}",
                        saleEventId,
                        productId,
                        userId,
                        replicationFailure.redisSeq()
                );
            }
        } catch (RuntimeException compensationFailure) {
            log.warn(
                    "Redis replication failure compensation failed; admission remains paused saleEventId={} productId={} userId={} redisSeq={}",
                    saleEventId,
                    productId,
                    userId,
                    replicationFailure.redisSeq(),
                    compensationFailure
            );
        }
    }

    private boolean tryRecoverRedisGate(long saleEventId, long productId) {
        if (!redisRecoveryProbePermit.tryAcquire()) {
            return false;
        }
        try {
            if (!redisAdmissionGateway.probeRecovery()) {
                rememberGateMode(saleEventId, productId, GateMode.REDIS_FAILOVER_PAUSED);
                return false;
            }
            dbWriteBulkhead.execute(() -> {
                transactionService.markRedisRecovered(saleEventId, productId);
                return null;
            });
            rememberGateMode(saleEventId, productId, GateMode.REDIS);
            clearLocalPause(saleEventId, productId);
            if (startLocalQueueRecoveryDrainIfNeeded()) {
                log.info(
                        "Redis admission recovered, but local queue backlog remains; keeping saleEventId={} productId={} in local drain window",
                        saleEventId,
                        productId
                );
                return false;
            }
            log.info(
                    "Redis admission recovered after half-open probe saleEventId={} productId={}",
                    saleEventId,
                    productId
            );
            return true;
        } catch (RedisSystemException | QueryTimeoutException | DataAccessResourceFailureException redisFailure) {
            rememberGateMode(saleEventId, productId, GateMode.REDIS_FAILOVER_PAUSED);
            log.warn(
                    "Redis admission half-open probe failed; keeping saleEventId={} productId={} paused",
                    saleEventId,
                    productId,
                    redisFailure
            );
            return false;
        } catch (BusinessException markerFailure) {
            if (markerFailure.getErrorCode() != BookingErrorCode.SERVICE_BUSY) {
                throw markerFailure;
            }
            rememberGateMode(saleEventId, productId, GateMode.REDIS_FAILOVER_PAUSED);
            log.warn(
                    "Redis recovery marker is busy; keeping saleEventId={} productId={} paused",
                    saleEventId,
                    productId,
                    markerFailure
            );
            return false;
        } catch (CannotCreateTransactionException markerFailure) {
            rememberGateMode(saleEventId, productId, GateMode.REDIS_FAILOVER_PAUSED);
            log.warn(
                    "Redis recovery marker could not be persisted; keeping saleEventId={} productId={} paused",
                    saleEventId,
                    productId,
                    markerFailure
            );
            return false;
        } finally {
            redisRecoveryProbePermit.release();
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
        if (gateModeCacheTtl.isZero() || gateModeCacheTtl.isNegative()) {
            gateModeCache.remove(cacheKey(saleEventId, productId));
            rememberLocalPause(saleEventId, productId, mode);
            return true;
        }
        Duration ttl = mode == GateMode.REDIS_FAILOVER_PAUSED
                ? properties.redisFailoverRetryAfter()
                : gateModeCacheTtl;
        if (ttl.isZero() || ttl.isNegative()) {
            ttl = gateModeCacheTtl;
        }
        CachedGateMode previous = gateModeCache.put(
                cacheKey(saleEventId, productId),
                new CachedGateMode(mode, System.nanoTime() + ttl.toNanos())
        );
        rememberLocalPause(saleEventId, productId, mode);
        return previous == null || previous.mode() != mode;
    }

    private boolean isLocallyPaused(long saleEventId, long productId) {
        String key = cacheKey(saleEventId, productId);
        Long pauseUntil = redisPauseUntilNanos.get(key);
        if (pauseUntil == null) {
            return false;
        }
        long now = System.nanoTime();
        if (pauseUntil > now) {
            return true;
        }
        redisPauseUntilNanos.remove(key, pauseUntil);
        return false;
    }

    private boolean startLocalQueueRecoveryDrainIfNeeded() {
        return localWaitingRoom != null && localWaitingRoom.markRedisRecovered();
    }

    private void rememberLocalPause(long saleEventId, long productId, GateMode mode) {
        if (mode != GateMode.REDIS_FAILOVER_PAUSED) {
            clearLocalPause(saleEventId, productId);
            return;
        }
        if (localWaitingRoom != null) {
            localWaitingRoom.markRedisUnavailable();
        }
        Duration ttl = properties.redisFailoverRetryAfter();
        if (ttl.isZero() || ttl.isNegative()) {
            ttl = Duration.ofSeconds(1);
        }
        redisPauseUntilNanos.put(cacheKey(saleEventId, productId), System.nanoTime() + ttl.toNanos());
    }

    private void clearLocalPause(long saleEventId, long productId) {
        redisPauseUntilNanos.remove(cacheKey(saleEventId, productId));
    }

    private String cacheKey(long saleEventId, long productId) {
        return saleEventId + ":" + productId;
    }

    private record CachedGateMode(GateMode mode, long expiresAtNanos) {
    }

    private static final class RedisAdmissionFailureException extends RuntimeException {

        RedisAdmissionFailureException(RuntimeException cause) {
            super(cause);
        }

        RuntimeException cause() {
            return (RuntimeException) getCause();
        }
    }

    private static final class AdmissionPersistenceUnavailableException extends RuntimeException {

        AdmissionPersistenceUnavailableException(RuntimeException cause) {
            super(cause);
        }

        RuntimeException cause() {
            return (RuntimeException) getCause();
        }
    }
}
