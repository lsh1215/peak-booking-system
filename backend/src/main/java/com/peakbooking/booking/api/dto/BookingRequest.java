package com.peakbooking.booking.api.dto;

import com.peakbooking.booking.application.MockPgScenario;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Positive;
import java.util.List;

public record BookingRequest(
        @Positive long saleEventId,
        @Positive long productId,
        @NotBlank String bookingAttemptId,
        @NotEmpty List<@Valid PaymentMethodRequest> paymentMethods,
        @Positive long totalAmount,
        @NotBlank String currency,
        MockPgScenario mockPgScenario
) {

    public MockPgScenario mockPgScenarioOrDefault() {
        return mockPgScenario == null ? MockPgScenario.SUCCESS : mockPgScenario;
    }
}
