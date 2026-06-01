package com.peakbooking.booking.application;

import com.peakbooking.booking.config.BookingProperties;
import com.peakbooking.booking.domain.AdmissionDecision;
import com.peakbooking.booking.domain.GateMode;
import com.peakbooking.booking.infrastructure.jdbc.BookingJdbcRepository;
import com.peakbooking.booking.redis.RedisAdmissionGateway;
import java.util.concurrent.Semaphore;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.RedisSystemException;
import org.springframework.stereotype.Service;

@Service
public class BookingAdmissionService {

    private final BookingProperties properties;
    private final BookingJdbcRepository repository;
    private final RedisAdmissionGateway redisAdmissionGateway;
    private final AdmissionTransactionService transactionService;
    private final Semaphore dbFallbackBulkhead = new Semaphore(2);

    public BookingAdmissionService(
            BookingProperties properties,
            BookingJdbcRepository repository,
            RedisAdmissionGateway redisAdmissionGateway,
            AdmissionTransactionService transactionService
    ) {
        this.properties = properties;
        this.repository = repository;
        this.redisAdmissionGateway = redisAdmissionGateway;
        this.transactionService = transactionService;
    }

    public AdmissionDecision admit(long saleEventId, long productId, long userId) {
        GateMode currentMode = repository.gateMode(saleEventId, productId);
        if (currentMode == GateMode.DB_FALLBACK) {
            return dbFallbackAdmission(saleEventId, productId, userId, false);
        }

        RedisAdmissionGateway.Result result;
        try {
            result = redisAdmissionGateway.tryAdmit(
                    productId,
                    saleEventId,
                    userId,
                    properties.candidateLimit()
            );
        } catch (RedisSystemException
                 | QueryTimeoutException
                 | DataAccessResourceFailureException redisFailure) {
            return dbFallbackAdmission(saleEventId, productId, userId, true);
        }
        if (!result.admitted()) {
            return AdmissionDecision.rejected(GateMode.REDIS);
        }
        return transactionService.createAdmission(
                saleEventId,
                productId,
                userId,
                GateMode.REDIS,
                result.redisSeq(),
                properties.candidateLimit()
        );
    }

    private AdmissionDecision dbFallbackAdmission(
            long saleEventId,
            long productId,
            long userId,
            boolean markFallback
    ) {
        if (!dbFallbackBulkhead.tryAcquire()) {
            return AdmissionDecision.rejected(GateMode.DB_FALLBACK);
        }
        try {
            if (markFallback) {
                return transactionService.markFallbackAndCreate(
                        saleEventId,
                        productId,
                        userId,
                        properties.candidateLimit()
                );
            }
            return transactionService.createAdmission(
                    saleEventId,
                    productId,
                    userId,
                    GateMode.DB_FALLBACK,
                    null,
                    properties.candidateLimit()
            );
        } finally {
            dbFallbackBulkhead.release();
        }
    }
}
