package com.peakbooking.booking.application.dto;

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
    public static final String LOCAL_QUEUE_ACCEPTED = "LOCAL_QUEUE_ACCEPTED";
    public static final String LOCAL_QUEUE_FULL = "LOCAL_QUEUE_FULL";

    public boolean terminal() {
        return !retryable;
    }
}
