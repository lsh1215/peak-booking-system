package com.peakbooking.booking.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
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
import com.peakbooking.booking.payment.CreditCardPaymentProcessor;
import com.peakbooking.booking.payment.PaymentCallGuard;
import com.peakbooking.booking.payment.PaymentProcessorRegistry;
import com.peakbooking.booking.payment.YPayPaymentProcessor;
import com.peakbooking.booking.payment.YPointPaymentProcessor;
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
        BookingDbWriteBulkhead bulkhead = new BookingDbWriteBulkhead(propertiesWithBulkhead(0, 5, 1, 2));

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
        BookingProperties properties = propertiesWithBulkhead(6, 5, 1, 2);
        Clock clock = Clock.fixed(Instant.parse("2026-06-01T00:00:00Z"), ZoneOffset.UTC);
        BookingApplicationService service = new BookingApplicationService(
                properties,
                repository,
                attemptTokenService,
                requestHashCalculator,
                new CombinationPolicy(),
                admissionService,
                new LocalWaitingRoom(properties),
                transactionService,
                new BookingDbWriteBulkhead(properties),
                paymentCallGuard,
                new PaymentProcessorRegistry(List.of(
                        new CreditCardPaymentProcessor(),
                        new YPayPaymentProcessor(),
                        new YPointPaymentProcessor()
                )),
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

    @Test
    void should_keep_new_requests_in_local_queue_until_existing_queue_is_drained_after_redis_recovery() {
        BookingJpaRepository repository = mock(BookingJpaRepository.class);
        AttemptTokenService attemptTokenService = mock(AttemptTokenService.class);
        CanonicalRequestHashCalculator requestHashCalculator = mock(CanonicalRequestHashCalculator.class);
        BookingAdmissionService admissionService = mock(BookingAdmissionService.class);
        BookingTransactionService transactionService = mock(BookingTransactionService.class);
        PaymentCallGuard paymentCallGuard = mock(PaymentCallGuard.class);
        BookingProperties properties = propertiesWithBulkhead(6, 5, 1, 2);
        LocalWaitingRoom localWaitingRoom = new LocalWaitingRoom(properties);
        Clock clock = Clock.fixed(Instant.parse("2026-06-01T00:00:00Z"), ZoneOffset.UTC);
        BookingApplicationService service = new BookingApplicationService(
                properties,
                repository,
                attemptTokenService,
                requestHashCalculator,
                new CombinationPolicy(),
                admissionService,
                localWaitingRoom,
                transactionService,
                new BookingDbWriteBulkhead(properties),
                paymentCallGuard,
                new PaymentProcessorRegistry(List.of(
                        new CreditCardPaymentProcessor(),
                        new YPayPaymentProcessor(),
                        new YPointPaymentProcessor()
                )),
                clock,
                Duration.ofSeconds(1)
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
        BookingCommand first = command(101, "token-101");
        BookingCommand second = command(102, "token-102");
        when(repository.findProduct(1)).thenReturn(Optional.of(product));
        when(attemptTokenService.verify("token-101", 101, 1, 1))
                .thenReturn(new AttemptToken("token-101", "attempt-101", 101, 1, 1));
        when(attemptTokenService.verify("token-102", 102, 1, 1))
                .thenReturn(new AttemptToken("token-102", "attempt-102", 102, 1, 1));
        when(requestHashCalculator.hash(first, "attempt-101")).thenReturn("hash-101");
        when(requestHashCalculator.hash(second, "attempt-102")).thenReturn("hash-102");
        when(admissionService.admit(1, 1, 101, "attempt-101"))
                .thenReturn(AdmissionDecision.rejected(GateMode.REDIS_FAILOVER_PAUSED));
        when(transactionService.replayExisting("attempt-101", "hash-101")).thenReturn(Optional.empty());

        BookingResult firstResult = service.book(first);
        BookingResult secondResult = service.book(second);

        assertThat(firstResult.businessCode()).isEqualTo(BookingResult.LOCAL_QUEUE_ACCEPTED);
        assertThat(secondResult.businessCode()).isEqualTo(BookingResult.LOCAL_QUEUE_ACCEPTED);
        assertThat(localWaitingRoom.activeCount()).isEqualTo(2);
        verify(admissionService, times(1)).admit(1, 1, 101, "attempt-101");
        verify(transactionService, times(1)).replayExisting("attempt-101", "hash-101");
        verifyNoInteractions(paymentCallGuard);
    }

    private BookingCommand command(long userId, String token) {
        return new BookingCommand(
                userId,
                1,
                1,
                token,
                PaymentPlan.from(List.of(new PaymentPlanLine(PaymentMethodType.CREDIT_CARD, 10_000))),
                10_000,
                "KRW",
                "v1",
                MockPgScenario.SUCCESS
        );
    }

    static BookingProperties propertiesWithBulkhead(
            int bookingWriteConcurrency,
            int pgConfirmConcurrency,
            int recoveryPgConcurrency,
            int checkoutReadConcurrency
    ) {
        return propertiesWithBulkhead(
                bookingWriteConcurrency,
                pgConfirmConcurrency,
                recoveryPgConcurrency,
                checkoutReadConcurrency,
                16,
                Duration.ofSeconds(2)
        );
    }

    static BookingProperties propertiesWithBulkhead(
            int bookingWriteConcurrency,
            int pgConfirmConcurrency,
            int recoveryPgConcurrency,
            int checkoutReadConcurrency,
            int redisAdmissionConcurrency,
            Duration redisFailoverRetryAfter
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
                redisFailoverRetryAfter,
                new BookingProperties.LocalQueue(
                        true,
                        2,
                        2,
                        1,
                        Duration.ofMillis(100),
                        Duration.ofSeconds(30),
                        Duration.ofSeconds(60)
                ),
                new BookingProperties.Bulkhead(
                        bookingWriteConcurrency,
                        pgConfirmConcurrency,
                        recoveryPgConcurrency,
                        checkoutReadConcurrency,
                        redisAdmissionConcurrency
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
