package com.peakbooking.booking.payment;

public record PaymentStatusResult(PaymentStatus status, String errorCode) {

    public static PaymentStatusResult approved() {
        return new PaymentStatusResult(PaymentStatus.APPROVED, null);
    }

    public static PaymentStatusResult failed(String errorCode) {
        return new PaymentStatusResult(PaymentStatus.FAILED, errorCode);
    }

    public static PaymentStatusResult cancelled() {
        return new PaymentStatusResult(PaymentStatus.CANCELLED, null);
    }

    public static PaymentStatusResult unknown(String errorCode) {
        return new PaymentStatusResult(PaymentStatus.UNKNOWN, errorCode);
    }
}
