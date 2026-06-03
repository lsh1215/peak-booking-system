package com.peakbooking.booking.payment;

import com.peakbooking.booking.domain.PaymentMethodType;

public record PaymentExecutionComponent(
        PaymentMethodType methodType,
        long amount,
        PaymentExecutionKind kind,
        String providerOrderId
) {

    public static PaymentExecutionComponent externalProvider(
            PaymentMethodType methodType,
            long amount,
            String providerOrderId
    ) {
        return new PaymentExecutionComponent(
                methodType,
                amount,
                PaymentExecutionKind.EXTERNAL_PROVIDER,
                providerOrderId
        );
    }

    public static PaymentExecutionComponent internalLedger(PaymentMethodType methodType, long amount) {
        return new PaymentExecutionComponent(methodType, amount, PaymentExecutionKind.INTERNAL_LEDGER, null);
    }

    public boolean isExternalProvider() {
        return kind == PaymentExecutionKind.EXTERNAL_PROVIDER;
    }
}
