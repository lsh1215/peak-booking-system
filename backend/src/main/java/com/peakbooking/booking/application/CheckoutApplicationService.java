package com.peakbooking.booking.application;

import com.peakbooking.booking.config.BookingProperties;
import com.peakbooking.booking.domain.BookingErrorCode;
import com.peakbooking.booking.infrastructure.jdbc.BookingJdbcRepository;
import com.peakbooking.common.exception.BusinessException;
import org.springframework.stereotype.Service;

@Service
public class CheckoutApplicationService {

    private final BookingProperties properties;
    private final BookingJdbcRepository repository;
    private final AttemptTokenService attemptTokenService;

    public CheckoutApplicationService(
            BookingProperties properties,
            BookingJdbcRepository repository,
            AttemptTokenService attemptTokenService
    ) {
        this.properties = properties;
        this.repository = repository;
        this.attemptTokenService = attemptTokenService;
    }

    public CheckoutResult checkout(long userId, long productId) {
        ProductSummary product = repository.findProduct(productId)
                .orElseThrow(() -> new BusinessException(BookingErrorCode.PRODUCT_NOT_FOUND));
        AttemptToken token = attemptTokenService.issue(userId, properties.saleEventId(), productId);
        long points = repository.availablePoints(userId);
        return new CheckoutResult(product, points, token.rawToken(), properties.saleEventId());
    }

    public record CheckoutResult(
            ProductSummary product,
            long availableYPoints,
            String bookingAttemptId,
            long saleEventId
    ) {
    }
}
