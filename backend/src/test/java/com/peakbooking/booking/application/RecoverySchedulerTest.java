package com.peakbooking.booking.application;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

import com.peakbooking.booking.config.BookingProperties;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.CannotCreateTransactionException;

class RecoverySchedulerTest {

    @Test
    void should_not_leak_expected_dependency_busy_exception_from_scheduler() {
        RecoveryWorkerService worker = mock(RecoveryWorkerService.class);
        doThrow(new CannotCreateTransactionException("pool busy"))
                .when(worker)
                .recoverDueReservations();
        RecoveryScheduler scheduler = new RecoveryScheduler(propertiesWithNoJitter(), worker);

        assertThatCode(scheduler::run).doesNotThrowAnyException();
    }

    private BookingProperties propertiesWithNoJitter() {
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
                base.bulkhead(),
                base.payment(),
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
