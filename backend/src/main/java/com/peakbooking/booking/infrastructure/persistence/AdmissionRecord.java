package com.peakbooking.booking.infrastructure.persistence;

import com.peakbooking.booking.domain.AdmissionStatus;
import java.time.LocalDateTime;

public record AdmissionRecord(
        long id,
        long saleEventId,
        long productId,
        long userId,
        Long dbAdmissionSeq,
        Long redisSeq,
        AdmissionStatus status,
        String bookingAttemptId,
        LocalDateTime waitingExpiresAt
) {
}
