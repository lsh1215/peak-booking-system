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
        Recovery recovery
) {

    public record Recovery(
            Duration fixedDelay,
            Duration initialJitter,
            int batchSize,
            Duration leaseTimeout,
            boolean enabled
    ) {
    }
}
