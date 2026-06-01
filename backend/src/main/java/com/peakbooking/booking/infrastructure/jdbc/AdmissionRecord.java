package com.peakbooking.booking.infrastructure.jdbc;

import com.peakbooking.booking.domain.AdmissionStatus;
import java.time.LocalDateTime;

public record AdmissionRecord(
        long id,
        long saleEventId,
        long productId,
        long userId,
        long dbAdmissionSeq,
        AdmissionStatus status,
        LocalDateTime waitingExpiresAt
) {
}
