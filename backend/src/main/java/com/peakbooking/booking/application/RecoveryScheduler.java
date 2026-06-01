package com.peakbooking.booking.application;

import com.peakbooking.booking.config.BookingProperties;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class RecoveryScheduler {

    private final BookingProperties properties;
    private final RecoveryWorkerService recoveryWorkerService;

    public RecoveryScheduler(BookingProperties properties, RecoveryWorkerService recoveryWorkerService) {
        this.properties = properties;
        this.recoveryWorkerService = recoveryWorkerService;
    }

    @Scheduled(fixedDelayString = "${peak-booking.recovery.fixed-delay:5s}")
    public void run() {
        if (properties.recovery().enabled()) {
            recoveryWorkerService.recoverDueReservations();
        }
    }
}
