package com.peakbooking.booking.domain;

import com.peakbooking.common.exception.ErrorCodeBase;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum BookingErrorCode implements ErrorCodeBase {

    INVALID_ATTEMPT_TOKEN(HttpStatus.BAD_REQUEST, "BOOKING_001", "Invalid booking attempt token"),
    PRODUCT_NOT_FOUND(HttpStatus.NOT_FOUND, "BOOKING_002", "Product not found"),
    PAYMENT_COMBINATION_NOT_ALLOWED(HttpStatus.UNPROCESSABLE_ENTITY, "BOOKING_003", "Payment combination is not allowed"),
    PAYMENT_AMOUNT_MISMATCH(HttpStatus.UNPROCESSABLE_ENTITY, "BOOKING_004", "Payment amount does not match total amount"),
    IDEMPOTENCY_CONFLICT(HttpStatus.CONFLICT, "BOOKING_005", "Idempotency request hash conflict"),
    SOLD_OUT(HttpStatus.CONFLICT, "BOOKING_006", "Limited stock is sold out"),
    POINTS_NOT_ENOUGH(HttpStatus.UNPROCESSABLE_ENTITY, "BOOKING_007", "Y points are not enough"),
    ADMISSION_CLOSED(HttpStatus.TOO_MANY_REQUESTS, "BOOKING_008", "Candidate pool is closed"),
    PAYMENT_FAILED(HttpStatus.UNPROCESSABLE_ENTITY, "BOOKING_009", "Payment failed");

    private final HttpStatus status;
    private final String code;
    private final String message;
}
