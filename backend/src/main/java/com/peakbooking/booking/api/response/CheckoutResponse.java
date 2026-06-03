package com.peakbooking.booking.api.response;

import com.peakbooking.booking.application.CheckoutApplicationService;
import java.time.LocalDateTime;

public record CheckoutResponse(
        long saleEventId,
        long productId,
        String productName,
        long priceAmount,
        String currency,
        LocalDateTime saleOpenAt,
        LocalDateTime checkInAt,
        LocalDateTime checkOutAt,
        long availableYPoints,
        String bookingAttemptId
) {

    public static CheckoutResponse from(CheckoutApplicationService.CheckoutResult result) {
        return new CheckoutResponse(
                result.saleEventId(),
                result.product().productId(),
                result.product().name(),
                result.product().priceAmount(),
                result.product().currency(),
                result.product().saleOpenAt(),
                result.product().checkInAt(),
                result.product().checkOutAt(),
                result.availableYPoints(),
                result.bookingAttemptId()
        );
    }
}
