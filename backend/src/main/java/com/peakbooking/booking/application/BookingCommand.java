package com.peakbooking.booking.application;

import com.peakbooking.booking.domain.PaymentPlan;

public record BookingCommand(
        long userId,
        long saleEventId,
        long productId,
        String bookingAttemptToken,
        PaymentPlan paymentPlan,
        long totalAmount,
        String currency,
        String paymentPolicyVersion,
        MockPgScenario mockPgScenario
) {
}
