package com.peakbooking.booking.application;

public record BookingResult(
        int httpStatus,
        String businessCode,
        String bookingAttemptId,
        Long reservationId,
        String reservationStatus,
        String paymentStatus,
        boolean retryable,
        String nextAction,
        String message
) {

    public static final String ADMISSION_TEMPORARILY_UNAVAILABLE = "ADMISSION_TEMPORARILY_UNAVAILABLE";

    public boolean terminal() {
        return !retryable;
    }
}
