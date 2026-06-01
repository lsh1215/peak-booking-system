package com.peakbooking.booking.domain;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

public record PaymentPlan(Map<PaymentMethodType, Long> amounts) {

    public static PaymentPlan from(List<PaymentPlanLine> lines) {
        EnumMap<PaymentMethodType, Long> result = new EnumMap<>(PaymentMethodType.class);
        for (PaymentPlanLine line : lines) {
            if (line.amount() <= 0) {
                throw new IllegalArgumentException("Payment amount must be positive");
            }
            result.merge(line.type(), line.amount(), Long::sum);
        }
        return new PaymentPlan(Map.copyOf(result));
    }

    public long amountOf(PaymentMethodType type) {
        return amounts.getOrDefault(type, 0L);
    }

    public long totalAmount() {
        return amounts.values().stream().mapToLong(Long::longValue).sum();
    }

    public boolean has(PaymentMethodType type) {
        return amountOf(type) > 0;
    }

    public List<PaymentPlanLine> orderedLines() {
        return amounts.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> new PaymentPlanLine(entry.getKey(), entry.getValue()))
                .toList();
    }
}
