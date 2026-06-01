package com.peakbooking.booking.infrastructure.persistence;

import com.peakbooking.booking.domain.IdempotencyStatus;

public record IdempotencyRecord(
        String bookingAttemptId,
        String requestHash,
        IdempotencyStatus status,
        Integer httpStatus,
        String businessCode,
        String responseSnapshot,
        Long reservationId
) {
}
