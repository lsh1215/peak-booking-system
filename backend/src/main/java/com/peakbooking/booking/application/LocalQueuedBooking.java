package com.peakbooking.booking.application;

record LocalQueuedBooking(
        BookingCommand command,
        String bookingAttemptId,
        String requestHash,
        long localSequence,
        int attemptCount,
        long firstQueuedAtNanos,
        long nextAttemptAtNanos
) {

    static LocalQueuedBooking initial(
            BookingCommand command,
            String bookingAttemptId,
            String requestHash,
            long localSequence,
            long nowNanos
    ) {
        return new LocalQueuedBooking(command, bookingAttemptId, requestHash, localSequence, 0, nowNanos, nowNanos);
    }

    LocalQueuedBooking retryAfter(long nowNanos, long backoffNanos) {
        return new LocalQueuedBooking(
                command,
                bookingAttemptId,
                requestHash,
                localSequence,
                attemptCount + 1,
                firstQueuedAtNanos,
                nowNanos + Math.max(0, backoffNanos)
        );
    }
}
