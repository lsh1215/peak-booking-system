package com.peakbooking.booking.payment;

public record PaymentConfirmResult(
        PaymentConfirmStatus status,
        String providerPaymentId,
        String errorCode
) {

    public static PaymentConfirmResult success(String providerPaymentId) {
        return new PaymentConfirmResult(PaymentConfirmStatus.SUCCESS, providerPaymentId, null);
    }

    public static PaymentConfirmResult failure(String errorCode) {
        return new PaymentConfirmResult(PaymentConfirmStatus.FAILURE, null, errorCode);
    }

    public static PaymentConfirmResult unknown(String providerPaymentId, String errorCode) {
        return new PaymentConfirmResult(PaymentConfirmStatus.UNKNOWN, providerPaymentId, errorCode);
    }
}
