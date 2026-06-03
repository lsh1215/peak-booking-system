package com.peakbooking.booking.application.localqueue;

import com.peakbooking.booking.application.dto.BookingResult;

public record LocalQueueSubmission(
        Status status,
        String bookingAttemptId,
        int queuePosition,
        BookingResult completedResult
) {

    public enum Status {
        ACCEPTED,
        ALREADY_ACCEPTED,
        ALREADY_COMPLETED,
        CONFLICT,
        FULL,
        DISABLED
    }
}
