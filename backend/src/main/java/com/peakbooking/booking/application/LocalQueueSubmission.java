package com.peakbooking.booking.application;

record LocalQueueSubmission(
        Status status,
        String bookingAttemptId,
        int queuePosition,
        BookingResult completedResult
) {

    enum Status {
        ACCEPTED,
        ALREADY_ACCEPTED,
        ALREADY_COMPLETED,
        CONFLICT,
        FULL,
        DISABLED
    }
}
