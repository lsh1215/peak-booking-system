package com.peakbooking.booking.api.response;

import com.peakbooking.booking.application.dto.BookingResult;

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
