package com.peakbooking.booking.infrastructure.persistence;

import com.peakbooking.booking.domain.ReservationStatus;
import java.time.LocalDateTime;

public record ReservationRecord(
        long id,
        long admissionId,
        String bookingAttemptId,
        long saleEventId,
        long productId,
        long userId,
        ReservationStatus status,
        LocalDateTime holdExpiresAt,
        LocalDateTime unknownInventoryDeadlineAt
) {
}
