package com.peakbooking.booking.infrastructure.jdbc;

public record RecoveryClaim(
        ReservationRecord reservation,
        PaymentAttemptRecord paymentAttempt,
        String leaseToken
) {
}
