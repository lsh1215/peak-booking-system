package com.peakbooking.booking.application.token;

public record AttemptToken(
        String rawToken,
        String attemptId,
        long userId,
        long saleEventId,
        long productId
) {
}
