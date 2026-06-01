package com.peakbooking.booking.payment;

public record PaymentStatusResult(PaymentStatus status, String providerPaymentId, String errorCode) {

    public static PaymentStatusResult approved() {
        return approved(null);
    }

    public static PaymentStatusResult approved(String providerPaymentId) {
        return new PaymentStatusResult(PaymentStatus.APPROVED, providerPaymentId, null);
    }

    public static PaymentStatusResult failed(String errorCode) {
        return new PaymentStatusResult(PaymentStatus.FAILED, null, errorCode);
    }

    public static PaymentStatusResult cancelled() {
        return new PaymentStatusResult(PaymentStatus.CANCELLED, null, null);
    }

    public static PaymentStatusResult unknown(String errorCode) {
        return new PaymentStatusResult(PaymentStatus.UNKNOWN, null, errorCode);
    }
}
