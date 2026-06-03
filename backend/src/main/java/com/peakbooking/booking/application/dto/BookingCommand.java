package com.peakbooking.booking.application.dto;

import com.peakbooking.booking.domain.PaymentPlan;
import com.peakbooking.booking.payment.MockPgScenario;

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
