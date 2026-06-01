package com.peakbooking.booking.infrastructure.jdbc;

import com.peakbooking.booking.domain.PaymentAttemptStatus;
import java.time.LocalDateTime;

public record PaymentAttemptRecord(
        long id,
        String bookingAttemptId,
        Long reservationId,
        PaymentAttemptStatus status,
        String providerPaymentId,
        LocalDateTime firstUnknownAt,
        LocalDateTime activeReconcileUntil,
        LocalDateTime nextReconcileAt,
        int reconcileAttemptCount,
        String leaseToken
) {
}
