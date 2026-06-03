package com.peakbooking.booking.redis;

import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Component
public class RedisAdmissionWarmup implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(RedisAdmissionWarmup.class);

    private final RedisAdmissionGateway redisAdmissionGateway;
    private final Duration warmupDuration;

    public RedisAdmissionWarmup(
            RedisAdmissionGateway redisAdmissionGateway,
            @Value("${peak-booking.redis-warmup-duration:2s}") Duration warmupDuration
    ) {
        this.redisAdmissionGateway = redisAdmissionGateway;
        this.warmupDuration = warmupDuration;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (warmupDuration.isZero() || warmupDuration.isNegative()) {
            return;
        }

        long deadline = System.nanoTime() + warmupDuration.toNanos();
        RuntimeException lastFailure = null;
        while (System.nanoTime() < deadline) {
            try {
                if (redisAdmissionGateway.ping()) {
                    log.info("Redis admission connection warmed");
                    return;
                }
            } catch (RuntimeException failure) {
                lastFailure = failure;
            }
            sleepBriefly();
        }

        log.warn(
                "Redis admission warmup did not complete; booking write path will pause admission while Redis failover is in progress",
                lastFailure
        );
    }

    private void sleepBriefly() {
        try {
            Thread.sleep(100);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
