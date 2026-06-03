package com.peakbooking.booking.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.peakbooking.booking.config.BookingProperties;
import com.peakbooking.booking.domain.PaymentMethodType;
import com.peakbooking.booking.domain.PaymentPlan;
import com.peakbooking.booking.domain.PaymentPlanLine;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class LocalWaitingRoomTest {

    @Test
    void should_prefer_local_queue_until_backlog_is_drained() {
        LocalWaitingRoom waitingRoom = new LocalWaitingRoom(properties(2, Duration.ofSeconds(30)));

        LocalQueueSubmission submitted = waitingRoom.enqueue(command(101), "attempt-101", "hash-101");

        assertThat(submitted.status()).isEqualTo(LocalQueueSubmission.Status.ACCEPTED);
        assertThat(waitingRoom.shouldPreferLocalQueue()).isTrue();

        LocalQueuedBooking queued = waitingRoom.dequeue();
        waitingRoom.complete(queued.bookingAttemptId(), result(queued.bookingAttemptId()));

        assertThat(waitingRoom.activeCount()).isZero();
        assertThat(waitingRoom.shouldPreferLocalQueue()).isFalse();
    }

    @Test
    void should_keep_preferring_local_queue_before_redis_recovery_even_after_drain_grace() throws Exception {
        LocalWaitingRoom waitingRoom = new LocalWaitingRoom(properties(2, Duration.ofMillis(1)));

        waitingRoom.enqueue(command(101), "attempt-101", "hash-101");
        Thread.sleep(5);

        assertThat(waitingRoom.activeCount()).isEqualTo(1);
        assertThat(waitingRoom.shouldPreferLocalQueue()).isTrue();
    }

    @Test
    void should_stop_preferring_local_queue_after_recovery_drain_grace_when_backlog_remains() throws Exception {
        LocalWaitingRoom waitingRoom = new LocalWaitingRoom(properties(2, Duration.ofMillis(1)));

        waitingRoom.enqueue(command(101), "attempt-101", "hash-101");
        assertThat(waitingRoom.markRedisRecovered()).isTrue();
        Thread.sleep(5);

        assertThat(waitingRoom.activeCount()).isEqualTo(1);
        assertThat(waitingRoom.shouldPreferLocalQueue()).isFalse();
    }

    @Test
    void should_restart_local_preference_when_redis_becomes_unavailable_again() throws Exception {
        LocalWaitingRoom waitingRoom = new LocalWaitingRoom(properties(2, Duration.ofMillis(1)));

        waitingRoom.enqueue(command(101), "attempt-101", "hash-101");
        assertThat(waitingRoom.markRedisRecovered()).isTrue();
        Thread.sleep(5);
        assertThat(waitingRoom.shouldPreferLocalQueue()).isFalse();

        waitingRoom.markRedisUnavailable();

        assertThat(waitingRoom.shouldPreferLocalQueue()).isTrue();
    }

    @Test
    void should_dedupe_same_attempt_and_reject_hash_conflict() {
        LocalWaitingRoom waitingRoom = new LocalWaitingRoom(properties(2, Duration.ofSeconds(30)));

        LocalQueueSubmission first = waitingRoom.enqueue(command(101), "attempt-101", "hash-101");
        LocalQueueSubmission duplicate = waitingRoom.enqueue(command(101), "attempt-101", "hash-101");
        LocalQueueSubmission conflict = waitingRoom.enqueue(command(101), "attempt-101", "different-hash");

        assertThat(first.status()).isEqualTo(LocalQueueSubmission.Status.ACCEPTED);
        assertThat(duplicate.status()).isEqualTo(LocalQueueSubmission.Status.ALREADY_ACCEPTED);
        assertThat(conflict.status()).isEqualTo(LocalQueueSubmission.Status.CONFLICT);
        assertThat(waitingRoom.activeCount()).isEqualTo(1);
    }

    @Test
    void should_shed_when_capacity_is_full() {
        LocalWaitingRoom waitingRoom = new LocalWaitingRoom(properties(1, Duration.ofSeconds(30)));

        LocalQueueSubmission first = waitingRoom.enqueue(command(101), "attempt-101", "hash-101");
        LocalQueueSubmission second = waitingRoom.enqueue(command(102), "attempt-102", "hash-102");

        assertThat(first.status()).isEqualTo(LocalQueueSubmission.Status.ACCEPTED);
        assertThat(second.status()).isEqualTo(LocalQueueSubmission.Status.FULL);
        assertThat(waitingRoom.activeCount()).isEqualTo(1);
    }

    @Test
    void should_shed_when_outage_acceptance_budget_is_exhausted_even_if_capacity_remains() {
        LocalWaitingRoom waitingRoom = new LocalWaitingRoom(properties(5, 2, Duration.ofSeconds(30)));

        waitingRoom.markRedisUnavailable();
        LocalQueueSubmission first = waitingRoom.enqueue(command(101), "attempt-101", "hash-101");
        LocalQueueSubmission second = waitingRoom.enqueue(command(102), "attempt-102", "hash-102");
        LocalQueueSubmission third = waitingRoom.enqueue(command(103), "attempt-103", "hash-103");

        assertThat(first.status()).isEqualTo(LocalQueueSubmission.Status.ACCEPTED);
        assertThat(second.status()).isEqualTo(LocalQueueSubmission.Status.ACCEPTED);
        assertThat(third.status()).isEqualTo(LocalQueueSubmission.Status.FULL);
        assertThat(waitingRoom.queuedCount()).isEqualTo(2);
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
                201,
                "BOOKING_CONFIRMED",
                attemptId,
                1L,
                "CONFIRMED",
                "CONFIRMED",
                false,
                "NONE",
                "Booking confirmed"
        );
    }

    private BookingProperties properties(int capacity, Duration drainGrace) {
        return properties(capacity, capacity, drainGrace);
    }

    private BookingProperties properties(int capacity, int maxAcceptedPerOutage, Duration drainGrace) {
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
                        capacity,
                        maxAcceptedPerOutage,
                        1,
                        Duration.ofMillis(100),
                        drainGrace,
                        3,
                        Duration.ofMillis(500),
                        Duration.ofSeconds(30),
                        Duration.ofSeconds(60)
                ),
                base.bulkhead(),
                base.payment(),
                base.recovery()
        );
    }
}
