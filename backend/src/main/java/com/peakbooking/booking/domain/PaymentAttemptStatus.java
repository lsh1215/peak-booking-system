package com.peakbooking.booking.domain;

public enum PaymentAttemptStatus {
    REQUESTED,
    CONFIRMED,
    FAILED,
    PAYMENT_UNKNOWN,
    RECONCILING_AFTER_RELEASE,
    LATE_SUCCESS_CANCEL_PENDING,
    CANCELLED_AFTER_RELEASE,
    MANUAL_REVIEW_REQUIRED
}
