package com.peakbooking.booking.application;

import com.peakbooking.booking.application.dto.ProductSummary;
import com.peakbooking.booking.application.token.AttemptToken;
import com.peakbooking.booking.application.token.AttemptTokenService;
import com.peakbooking.booking.config.BookingProperties;
import com.peakbooking.booking.domain.AdmissionStatus;
import com.peakbooking.booking.domain.BookingErrorCode;
import com.peakbooking.booking.infrastructure.jpa.BookingJpaRepository;
import com.peakbooking.common.exception.BusinessException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.concurrent.Semaphore;
import org.springframework.stereotype.Service;

@Service
public class CheckoutApplicationService {

    private final BookingProperties properties;
    private final BookingJpaRepository repository;
    private final AttemptTokenService attemptTokenService;
    private final Clock clock;
    private final Semaphore checkoutReadBulkhead;

    public CheckoutApplicationService(
            BookingProperties properties,
            BookingJpaRepository repository,
            AttemptTokenService attemptTokenService,
            Clock clock
    ) {
        this.properties = properties;
        this.repository = repository;
        this.attemptTokenService = attemptTokenService;
        this.clock = clock;
        this.checkoutReadBulkhead = new Semaphore(properties.bulkhead().checkoutReadConcurrency());
    }

    public CheckoutResult checkout(long userId, long productId) {
        if (!checkoutReadBulkhead.tryAcquire()) {
            throw new BusinessException(BookingErrorCode.SERVICE_BUSY, "Checkout read path is busy");
        }
        try {
            return doCheckout(userId, productId);
        } finally {
            checkoutReadBulkhead.release();
        }
    }

    private CheckoutResult doCheckout(long userId, long productId) {
        ProductSummary product = repository.findProduct(productId)
                .orElseThrow(() -> new BusinessException(BookingErrorCode.PRODUCT_NOT_FOUND));
        AttemptToken token = reusableAttemptId(userId, productId)
                .map(attemptId -> attemptTokenService.issueForAttemptId(
                        attemptId,
                        userId,
                        properties.saleEventId(),
                        productId
                ))
                .orElseGet(() -> attemptTokenService.issue(userId, properties.saleEventId(), productId));
        long points = repository.availablePoints(userId);
        return new CheckoutResult(product, points, token.rawToken(), properties.saleEventId());
    }

    private Optional<String> reusableAttemptId(long userId, long productId) {
        return repository.findAdmissionRecord(properties.saleEventId(), productId, userId)
                .filter(admission -> admission.bookingAttemptId() != null)
                .filter(admission -> admission.status() == AdmissionStatus.ADMITTED
                        || (admission.status() == AdmissionStatus.WAITING_CANDIDATE
                        && admission.waitingExpiresAt() != null
                        && admission.waitingExpiresAt().isAfter(LocalDateTime.now(clock))))
                .map(admission -> admission.bookingAttemptId());
    }

    public record CheckoutResult(
            ProductSummary product,
            long availableYPoints,
            String bookingAttemptId,
            long saleEventId
    ) {
    }
}
