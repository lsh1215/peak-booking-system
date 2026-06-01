package com.peakbooking.booking.api.dto;

import com.peakbooking.booking.application.BookingResult;

public record BookingResponse(
        String businessCode,
        String bookingAttemptId,
        Long reservationId,
        String reservationStatus,
        String paymentStatus,
        boolean retryable,
        String nextAction,
        String message
) {

    public static BookingResponse from(BookingResult result) {
        return new BookingResponse(
                result.businessCode(),
                result.bookingAttemptId(),
                result.reservationId(),
                result.reservationStatus(),
                result.paymentStatus(),
                result.retryable(),
                result.nextAction(),
                result.message()
        );
    }
}
