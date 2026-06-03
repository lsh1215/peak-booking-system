package com.peakbooking.booking.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.peakbooking.booking.api.dto.BookingRequest;
import com.peakbooking.booking.api.dto.BookingResponse;
import com.peakbooking.booking.api.dto.PaymentMethodRequest;
import com.peakbooking.booking.application.BookingApplicationService;
import com.peakbooking.booking.application.BookingResult;
import com.peakbooking.booking.config.BookingProperties;
import com.peakbooking.booking.domain.PaymentMethodType;
import com.peakbooking.common.dto.ApiResponse;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

class BookingControllerTest {

    @Test
    void should_include_retry_after_header_when_redis_failover_is_paused() {
        BookingApplicationService service = mock(BookingApplicationService.class);
        BookingController controller = new BookingController(
                service,
                properties(Duration.ofSeconds(2))
        );
        when(service.book(any())).thenReturn(new BookingResult(
                503,
                BookingResult.ADMISSION_TEMPORARILY_UNAVAILABLE,
                "attempt-1",
                null,
                null,
                null,
                true,
                "RETRY_AFTER_SHORT_PAUSE",
                "Redis failover is in progress"
        ));

        ResponseEntity<ApiResponse<BookingResponse>> response = controller.book(1, new BookingRequest(
                1,
                1,
                "attempt-token",
                List.of(new PaymentMethodRequest(PaymentMethodType.CREDIT_CARD, 10_000)),
                10_000,
                "KRW",
                null
        ));

        assertThat(response.getStatusCode().value()).isEqualTo(503);
        assertThat(response.getHeaders().getFirst("Retry-After")).isEqualTo("2");
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getData().businessCode())
                .isEqualTo(BookingResult.ADMISSION_TEMPORARILY_UNAVAILABLE);
    }

    @Test
    void should_round_retry_after_up_to_at_least_one_second() {
        BookingApplicationService service = mock(BookingApplicationService.class);
        BookingController controller = new BookingController(
                service,
                properties(Duration.ofMillis(500))
        );
        when(service.book(any())).thenReturn(new BookingResult(
                503,
                BookingResult.ADMISSION_TEMPORARILY_UNAVAILABLE,
                "attempt-1",
                null,
                null,
                null,
                true,
                "RETRY_AFTER_SHORT_PAUSE",
                "Redis failover is in progress"
        ));

        ResponseEntity<ApiResponse<BookingResponse>> response = controller.book(1, new BookingRequest(
                1,
                1,
                "attempt-token",
                List.of(new PaymentMethodRequest(PaymentMethodType.CREDIT_CARD, 10_000)),
                10_000,
                "KRW",
                null
        ));

        assertThat(response.getHeaders().getFirst("Retry-After")).isEqualTo("1");
    }

    private BookingProperties properties(Duration redisFailoverRetryAfter) {
        return new BookingProperties(
                1,
                30,
                10,
                "v1",
                "test-secret",
                Duration.ofSeconds(30),
                Duration.ofSeconds(60),
                Duration.ofHours(24),
                Duration.ofMinutes(5),
                redisFailoverRetryAfter,
                new BookingProperties.LocalQueue(
                        true,
                        2,
                        1,
                        Duration.ofMillis(100),
                        Duration.ofSeconds(30),
                        Duration.ofSeconds(60)
                ),
                new BookingProperties.Bulkhead(6, 5, 1, 2, 16),
                new BookingProperties.Payment(
                        Duration.ofMillis(500),
                        Duration.ofMillis(50),
                        Duration.ofMillis(100),
                        Duration.ofMillis(600)
                ),
                new BookingProperties.Recovery(
                        Duration.ofSeconds(5),
                        Duration.ZERO,
                        5,
                        Duration.ofSeconds(30),
                        true
                )
        );
    }
}
