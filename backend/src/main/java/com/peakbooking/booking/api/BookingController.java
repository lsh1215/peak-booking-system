package com.peakbooking.booking.api;

import com.peakbooking.booking.api.dto.BookingRequest;
import com.peakbooking.booking.api.dto.BookingResponse;
import com.peakbooking.booking.application.BookingApplicationService;
import com.peakbooking.booking.application.BookingCommand;
import com.peakbooking.booking.application.BookingResult;
import com.peakbooking.booking.config.BookingProperties;
import com.peakbooking.booking.domain.PaymentPlan;
import com.peakbooking.booking.domain.PaymentPlanLine;
import com.peakbooking.common.dto.ApiResponse;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/bookings")
public class BookingController {

    private final BookingApplicationService bookingApplicationService;
    private final BookingProperties properties;

    public BookingController(BookingApplicationService bookingApplicationService, BookingProperties properties) {
        this.bookingApplicationService = bookingApplicationService;
        this.properties = properties;
    }

    @PostMapping
    public ResponseEntity<ApiResponse<BookingResponse>> book(
            @RequestHeader("X-User-Id") long userId,
            @Valid @RequestBody BookingRequest request
    ) {
        List<PaymentPlanLine> lines = request.paymentMethods().stream()
                .map(payment -> new PaymentPlanLine(payment.type(), payment.amount()))
                .toList();
        BookingCommand command = new BookingCommand(
                userId,
                request.saleEventId(),
                request.productId(),
                request.bookingAttemptId(),
                PaymentPlan.from(lines),
                request.totalAmount(),
                request.currency(),
                properties.paymentPolicyVersion(),
                request.mockPgScenarioOrDefault()
        );
        BookingResult result = bookingApplicationService.book(command);
        ResponseEntity.BodyBuilder response = ResponseEntity.status(HttpStatus.valueOf(result.httpStatus()));
        if (BookingResult.ADMISSION_TEMPORARILY_UNAVAILABLE.equals(result.businessCode())) {
            response.header("Retry-After", Long.toString(retryAfterSeconds()));
        }
        return response.body(ApiResponse.ok(BookingResponse.from(result)));
    }

    private long retryAfterSeconds() {
        long millis = properties.redisFailoverRetryAfter().toMillis();
        return Math.max(1, (millis + 999) / 1000);
    }
}
