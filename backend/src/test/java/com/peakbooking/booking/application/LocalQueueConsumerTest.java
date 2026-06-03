package com.peakbooking.booking.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.peakbooking.booking.application.dto.BookingCommand;
import com.peakbooking.booking.application.dto.BookingResult;
import com.peakbooking.booking.application.localqueue.LocalQueueConsumer;
import com.peakbooking.booking.application.localqueue.LocalWaitingRoom;
import com.peakbooking.booking.config.BookingProperties;
import com.peakbooking.booking.domain.PaymentMethodType;
import com.peakbooking.booking.domain.PaymentPlan;
import com.peakbooking.booking.domain.PaymentPlanLine;
import com.peakbooking.booking.payment.MockPgScenario;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.dao.QueryTimeoutException;

class LocalQueueConsumerTest {

    @Test
    void should_process_only_configured_batch_per_drain() {
        BookingProperties properties = properties();
        LocalWaitingRoom waitingRoom = new LocalWaitingRoom(properties);
        BookingApplicationService bookingApplicationService = mock(BookingApplicationService.class);
        LocalQueueConsumer consumer = new LocalQueueConsumer(properties, waitingRoom, bookingApplicationService);
        when(bookingApplicationService.processLocalQueued(any())).thenReturn(result("attempt"));
        waitingRoom.enqueue(command(101), "attempt-101", "hash-101");
        waitingRoom.enqueue(command(102), "attempt-102", "hash-102");

        int processed = consumer.drainOnce();

        assertThat(processed).isEqualTo(1);
        assertThat(waitingRoom.activeCount()).isEqualTo(1);
        verify(bookingApplicationService, times(1)).processLocalQueued(any());
    }

    @Test
    void should_requeue_transient_db_failure_without_completing_attempt() {
        BookingProperties properties = properties();
        LocalWaitingRoom waitingRoom = new LocalWaitingRoom(properties);
        BookingApplicationService bookingApplicationService = mock(BookingApplicationService.class);
        LocalQueueConsumer consumer = new LocalQueueConsumer(properties, waitingRoom, bookingApplicationService);
        BookingCommand command = command(101);
        waitingRoom.enqueue(command, "attempt-101", "hash-101");
        when(bookingApplicationService.processLocalQueued(command))
                .thenThrow(new QueryTimeoutException("db busy"))
                .thenReturn(result("attempt-101"));

        int firstDrain = consumer.drainOnce();
        int secondDrain = consumer.drainOnce();

        assertThat(firstDrain).isEqualTo(1);
        assertThat(secondDrain).isEqualTo(1);
        assertThat(waitingRoom.activeCount()).isZero();
        assertThat(waitingRoom.status("attempt-101").orElseThrow().businessCode())
                .isEqualTo("BOOKING_IN_PROGRESS");
        verify(bookingApplicationService, times(2)).processLocalQueued(command);
    }

    @Test
    void should_complete_as_unavailable_after_retry_budget_is_exhausted() {
        BookingProperties properties = properties(1, Duration.ZERO, Duration.ofSeconds(30));
        LocalWaitingRoom waitingRoom = new LocalWaitingRoom(properties);
        BookingApplicationService bookingApplicationService = mock(BookingApplicationService.class);
        LocalQueueConsumer consumer = new LocalQueueConsumer(properties, waitingRoom, bookingApplicationService);
        BookingCommand command = command(101);
        waitingRoom.enqueue(command, "attempt-101", "hash-101");
        when(bookingApplicationService.processLocalQueued(command))
                .thenThrow(new QueryTimeoutException("db busy"));

        consumer.drainOnce();
        consumer.drainOnce();

        assertThat(waitingRoom.activeCount()).isZero();
        assertThat(waitingRoom.status("attempt-101").orElseThrow().businessCode())
                .isEqualTo("LOCAL_QUEUE_PROCESSING_UNAVAILABLE");
        verify(bookingApplicationService, times(2)).processLocalQueued(command);
    }

    @Test
    void should_wait_for_retry_backoff_before_processing_again() {
        BookingProperties properties = properties(3, Duration.ofSeconds(30), Duration.ofSeconds(30));
        LocalWaitingRoom waitingRoom = new LocalWaitingRoom(properties);
        BookingApplicationService bookingApplicationService = mock(BookingApplicationService.class);
        LocalQueueConsumer consumer = new LocalQueueConsumer(properties, waitingRoom, bookingApplicationService);
        BookingCommand command = command(101);
        waitingRoom.enqueue(command, "attempt-101", "hash-101");
        when(bookingApplicationService.processLocalQueued(command))
                .thenThrow(new QueryTimeoutException("db busy"));

        consumer.drainOnce();
        int skipped = consumer.drainOnce();

        assertThat(skipped).isZero();
        assertThat(waitingRoom.activeCount()).isEqualTo(1);
        verify(bookingApplicationService, times(1)).processLocalQueued(command);
    }

    private BookingCommand command(long userId) {
        return new BookingCommand(
                userId,
                1,
                1,
                "token-" + userId,
                PaymentPlan.from(List.of(new PaymentPlanLine(PaymentMethodType.CREDIT_CARD, 10_000))),
                10_000,
                "KRW",
                "v1",
                MockPgScenario.SUCCESS
        );
    }

    private BookingResult result(String attemptId) {
        return new BookingResult(
                202,
                "BOOKING_IN_PROGRESS",
                attemptId,
                null,
                null,
                null,
                true,
                "POLL_BOOKING_STATUS",
                "Booking is still being processed"
        );
    }

    private BookingProperties properties() {
        return properties(3, Duration.ZERO, Duration.ofSeconds(30));
    }

    private BookingProperties properties(int maxRetryAttempts, Duration retryBackoff, Duration maxRetryAge) {
        BookingProperties base = BookingApplicationServiceTest.propertiesWithBulkhead(6, 5, 1, 2);
        return new BookingProperties(
                base.saleEventId(),
                base.candidateLimit(),
                base.totalStock(),
                base.paymentPolicyVersion(),
                base.attemptTokenSecret(),
                base.holdTimeout(),
                base.waitingTimeout(),
                base.idempotencyRetention(),
                base.reconciliationWindow(),
                base.redisFailoverRetryAfter(),
                new BookingProperties.LocalQueue(
                        true,
                        10,
                        10,
                        1,
                        Duration.ofMillis(100),
                        Duration.ofSeconds(30),
                        maxRetryAttempts,
                        retryBackoff,
                        maxRetryAge,
                        Duration.ofSeconds(60)
                ),
                base.bulkhead(),
                base.payment(),
                base.recovery()
        );
    }
}
