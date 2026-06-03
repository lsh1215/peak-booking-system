package com.peakbooking.booking.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "peak-booking")
public record BookingProperties(
        long saleEventId,
        int candidateLimit,
        int totalStock,
        String paymentPolicyVersion,
        String attemptTokenSecret,
        Duration holdTimeout,
        Duration waitingTimeout,
        Duration idempotencyRetention,
        Duration reconciliationWindow,
        Duration redisFailoverRetryAfter,
        LocalQueue localQueue,
        Bulkhead bulkhead,
        Payment payment,
        Recovery recovery
) {

    public record Bulkhead(
            int bookingWriteConcurrency,
            int pgConfirmConcurrency,
            int recoveryPgConcurrency,
            int checkoutReadConcurrency,
            int redisAdmissionConcurrency
    ) {
    }

    public record LocalQueue(
            boolean enabled,
            int capacity,
            int maxAcceptedPerOutage,
            int workerBatchSize,
            Duration workerFixedDelay,
            Duration drainGrace,
            Duration resultRetention
    ) {
    }

    public record Payment(
            Duration callTimeout,
            Duration confirmRecoveryGrace,
            Duration mockNormalDelay,
            Duration mockTimeoutDelay
    ) {
    }

    public record Recovery(
            Duration fixedDelay,
            Duration initialJitter,
            int batchSize,
            Duration leaseTimeout,
            boolean enabled
    ) {
    }
}
