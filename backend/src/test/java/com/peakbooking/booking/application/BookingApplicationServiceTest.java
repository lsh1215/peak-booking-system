package com.peakbooking.booking.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.peakbooking.booking.config.BookingProperties;
import com.peakbooking.booking.domain.AdmissionDecision;
import com.peakbooking.booking.domain.BookingErrorCode;
import com.peakbooking.booking.domain.CombinationPolicy;
import com.peakbooking.booking.domain.GateMode;
import com.peakbooking.booking.domain.PaymentMethodType;
import com.peakbooking.booking.domain.PaymentPlan;
import com.peakbooking.booking.domain.PaymentPlanLine;
import com.peakbooking.booking.infrastructure.jpa.BookingJpaRepository;
import com.peakbooking.booking.payment.PaymentCallGuard;
import com.peakbooking.common.exception.BusinessException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class BookingApplicationServiceTest {

    @Test
    void should_reject_db_write_when_db_write_bulkhead_is_full() {
        BookingDbWriteBulkhead bulkhead = new BookingDbWriteBulkhead(propertiesWithBulkhead(0, 2, 5, 1, 2));

        assertThatThrownBy(() -> bulkhead.execute(() -> "write"))
                .isInstanceOfSatisfying(BusinessException.class, exception -> {
                    assertThat(exception.getErrorCode()).isEqualTo(BookingErrorCode.SERVICE_BUSY);
                    assertThat(exception.getMessage()).contains("DB write path");
                });
    }

    @Test
    void should_cache_product_lookup_before_closed_candidate_pool_rejections() {
        BookingJpaRepository repository = mock(BookingJpaRepository.class);
        AttemptTokenService attemptTokenService = mock(AttemptTokenService.class);
        CanonicalRequestHashCalculator requestHashCalculator = mock(CanonicalRequestHashCalculator.class);
        BookingAdmissionService admissionService = mock(BookingAdmissionService.class);
        BookingTransactionService transactionService = mock(BookingTransactionService.class);
        PaymentCallGuard paymentCallGuard = mock(PaymentCallGuard.class);
        BookingProperties properties = propertiesWithBulkhead(6, 2, 5, 1, 2);
        Clock clock = Clock.fixed(Instant.parse("2026-06-01T00:00:00Z"), ZoneOffset.UTC);
        BookingApplicationService service = new BookingApplicationService(
                properties,
                repository,
                attemptTokenService,
                requestHashCalculator,
                new CombinationPolicy(),
                admissionService,
                transactionService,
                new BookingDbWriteBulkhead(properties),
                paymentCallGuard,
                clock,
                Duration.ofSeconds(1)
        );
        BookingCommand first = new BookingCommand(
                101,
                1,
                1,
                "token",
                PaymentPlan.from(List.of(new PaymentPlanLine(PaymentMethodType.CREDIT_CARD, 10_000))),
                10_000,
                "KRW",
                "v1",
                MockPgScenario.SUCCESS
        );
        BookingCommand second = new BookingCommand(
                102,
                1,
                1,
                "token-2",
                PaymentPlan.from(List.of(new PaymentPlanLine(PaymentMethodType.CREDIT_CARD, 10_000))),
                10_000,
                "KRW",
                "v1",
                MockPgScenario.SUCCESS
        );
        ProductSummary product = new ProductSummary(
                1,
                "Peak Room",
                10_000,
                "KRW",
                LocalDateTime.of(2026, 5, 31, 23, 59),
                LocalDateTime.of(2026, 6, 2, 15, 0),
                LocalDateTime.of(2026, 6, 3, 11, 0)
        );
        when(repository.findProduct(1)).thenReturn(Optional.of(product));
        when(attemptTokenService.verify("token", 101, 1, 1))
                .thenReturn(new AttemptToken("token", "attempt-101", 101, 1, 1));
        when(attemptTokenService.verify("token-2", 102, 1, 1))
                .thenReturn(new AttemptToken("token-2", "attempt-102", 102, 1, 1));
        when(requestHashCalculator.hash(first, "attempt-101")).thenReturn("hash-1");
        when(requestHashCalculator.hash(second, "attempt-102")).thenReturn("hash-2");
        when(admissionService.admit(1, 1, 101, "attempt-101"))
                .thenReturn(AdmissionDecision.rejected(GateMode.REDIS));
        when(admissionService.admit(1, 1, 102, "attempt-102"))
                .thenReturn(AdmissionDecision.rejected(GateMode.REDIS));

        BookingResult firstResult = service.book(first);
        BookingResult secondResult = service.book(second);

        assertThat(firstResult.httpStatus()).isEqualTo(429);
        assertThat(secondResult.httpStatus()).isEqualTo(429);
        org.mockito.Mockito.verify(repository, org.mockito.Mockito.times(1)).findProduct(1);
        verifyNoInteractions(transactionService, paymentCallGuard);
    }

    static BookingProperties propertiesWithBulkhead(
            int bookingWriteConcurrency,
            int dbFallbackConcurrency,
            int pgConfirmConcurrency,
            int recoveryPgConcurrency,
            int checkoutReadConcurrency
    ) {
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
                new BookingProperties.Bulkhead(
                        bookingWriteConcurrency,
                        dbFallbackConcurrency,
                        pgConfirmConcurrency,
                        recoveryPgConcurrency,
                        checkoutReadConcurrency
                ),
                new BookingProperties.Payment(
                        Duration.ofMillis(500),
                        Duration.ofMillis(50),
                        Duration.ofMillis(100),
                        Duration.ofMillis(600)
                ),
                new BookingProperties.Recovery(
                        Duration.ofSeconds(5),
                        Duration.ofSeconds(5),
                        5,
                        Duration.ofSeconds(30),
                        true
                )
        );
    }

}
