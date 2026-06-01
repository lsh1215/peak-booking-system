package com.peakbooking.booking.domain;

public record PaymentPlanLine(PaymentMethodType type, long amount) {
}
