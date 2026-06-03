package com.peakbooking.booking.application;

record LocalQueuedBooking(
        BookingCommand command,
        String bookingAttemptId,
        String requestHash,
        long localSequence
) {
}
