package com.peakbooking.booking.application;

import com.peakbooking.booking.config.BookingProperties;
import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.CannotCreateTransactionException;

@Slf4j
@Component
public class RecoveryScheduler {

    private final BookingProperties properties;
    private final RecoveryWorkerService recoveryWorkerService;
    private final AtomicBoolean firstRun = new AtomicBoolean(true);

    public RecoveryScheduler(BookingProperties properties, RecoveryWorkerService recoveryWorkerService) {
        this.properties = properties;
        this.recoveryWorkerService = recoveryWorkerService;
    }

    @Scheduled(fixedDelayString = "${peak-booking.recovery.fixed-delay:5s}")
    public void run() {
        if (properties.recovery().enabled()) {
            applyInitialJitterOnce();
            try {
                recoveryWorkerService.recoverDueReservations();
            } catch (CannotCreateTransactionException
                     | DataAccessResourceFailureException
                     | QueryTimeoutException e) {
                log.warn("Recovery skipped because dependency is busy: {}", e.getMessage());
            }
        }
    }

    private void applyInitialJitterOnce() {
        if (!firstRun.compareAndSet(true, false)) {
            return;
        }
        Duration jitter = properties.recovery().initialJitter();
        if (jitter == null || jitter.isZero() || jitter.isNegative()) {
            return;
        }
        long delayMillis = ThreadLocalRandom.current().nextLong(jitter.toMillis() + 1);
        if (delayMillis == 0) {
            return;
        }
        try {
            Thread.sleep(delayMillis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
