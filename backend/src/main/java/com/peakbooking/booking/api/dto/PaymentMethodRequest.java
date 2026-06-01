package com.peakbooking.booking.api.dto;

import com.peakbooking.booking.domain.PaymentMethodType;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record PaymentMethodRequest(
        @NotNull PaymentMethodType type,
        @Positive long amount
) {
}
