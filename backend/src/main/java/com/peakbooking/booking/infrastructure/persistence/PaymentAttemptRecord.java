package com.peakbooking.booking.infrastructure.persistence;

import com.peakbooking.booking.domain.PaymentAttemptStatus;
import java.time.LocalDateTime;

public record PaymentAttemptRecord(
        long id,
        String bookingAttemptId,
        Long reservationId,
        PaymentAttemptStatus status,
        String providerOrderId,
        String providerPaymentId,
        LocalDateTime firstUnknownAt,
        LocalDateTime activeReconcileUntil,
        LocalDateTime nextReconcileAt,
        int reconcileAttemptCount,
        LocalDateTime confirmStartedAt,
        String leaseToken
) {
}
