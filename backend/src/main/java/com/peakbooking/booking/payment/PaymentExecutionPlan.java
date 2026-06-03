package com.peakbooking.booking.payment;

import com.peakbooking.booking.domain.PaymentMethodType;
import java.util.List;
import java.util.Optional;

public record PaymentExecutionPlan(List<PaymentExecutionComponent> components) {

    public PaymentExecutionPlan {
        components = List.copyOf(components);
    }

    public Optional<PaymentExecutionComponent> externalComponent() {
        return components.stream()
                .filter(PaymentExecutionComponent::isExternalProvider)
                .findFirst();
    }

    public boolean requiresExternalConfirmation() {
        return externalComponent().isPresent();
    }

    public long pointAmount() {
        return components.stream()
                .filter(component -> component.methodType() == PaymentMethodType.Y_POINT)
                .mapToLong(PaymentExecutionComponent::amount)
                .sum();
    }
}
