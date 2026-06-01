package com.peakbooking.booking.application;

public record AttemptToken(
        String rawToken,
        String attemptId,
        long userId,
        long saleEventId,
        long productId
) {
}
