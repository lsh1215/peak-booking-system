package com.peakbooking.booking.infrastructure.persistence;

public record RecoveryClaim(
        ReservationRecord reservation,
        PaymentAttemptRecord paymentAttempt,
        String leaseToken
) {
}
