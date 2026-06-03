package com.peakbooking.booking.application.dto;

import java.time.LocalDateTime;

public record ProductSummary(
        long productId,
        String name,
        long priceAmount,
        String currency,
        LocalDateTime saleOpenAt,
        LocalDateTime checkInAt,
        LocalDateTime checkOutAt
) {
}
