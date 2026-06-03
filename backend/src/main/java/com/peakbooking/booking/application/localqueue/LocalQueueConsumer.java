package com.peakbooking.booking.application.localqueue;

import com.peakbooking.booking.application.BookingApplicationService;
import com.peakbooking.booking.application.dto.BookingResult;
import com.peakbooking.booking.config.BookingProperties;
import com.peakbooking.booking.domain.BookingErrorCode;
import com.peakbooking.common.exception.BusinessException;
import java.time.Duration;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.CannotCreateTransactionException;

@Slf4j
@Component
public class LocalQueueConsumer {

    private final BookingProperties properties;
    private final LocalWaitingRoom waitingRoom;
    private final BookingApplicationService bookingApplicationService;

    public LocalQueueConsumer(
            BookingProperties properties,
            LocalWaitingRoom waitingRoom,
            BookingApplicationService bookingApplicationService
    ) {
        this.properties = properties;
        this.waitingRoom = waitingRoom;
        this.bookingApplicationService = bookingApplicationService;
    }

    @Scheduled(fixedDelayString = "${peak-booking.local-queue.worker-fixed-delay:100ms}")
    public void drainScheduled() {
        if (properties.localQueue().enabled()) {
            drainOnce();
        }
    }

    public int drainOnce() {
        int processed = 0;
        int batchSize = Math.max(1, properties.localQueue().workerBatchSize());
        for (int i = 0; i < batchSize; i++) {
            LocalQueuedBooking queued = waitingRoom.dequeue();
            if (queued == null) {
                return processed;
            }
            ProcessingOutcome outcome = process(queued);
            if (outcome.shouldRetry()) {
                requeueOrComplete(queued);
            } else {
                waitingRoom.complete(queued.bookingAttemptId(), outcome.result());
            }
            processed++;
        }
        return processed;
    }

    private ProcessingOutcome process(LocalQueuedBooking queued) {
        try {
            return ProcessingOutcome.complete(bookingApplicationService.processLocalQueued(queued.command()));
        } catch (CannotCreateTransactionException
                 | DataAccessResourceFailureException
                 | QueryTimeoutException failure) {
            log.warn(
                    "Local queue processing skipped because dependency is busy bookingAttemptId={}",
                    queued.bookingAttemptId(),
                    failure
            );
            return ProcessingOutcome.retryLater();
        } catch (BusinessException failure) {
            if (failure.getErrorCode() == BookingErrorCode.SERVICE_BUSY) {
                log.warn(
                        "Local queue processing skipped because service is busy bookingAttemptId={}",
                        queued.bookingAttemptId(),
                        failure
                );
                return ProcessingOutcome.retryLater();
            }
            log.warn("Local queue processing failed bookingAttemptId={}", queued.bookingAttemptId(), failure);
            return ProcessingOutcome.complete(failed(queued.bookingAttemptId()));
        } catch (RuntimeException failure) {
            log.warn("Local queue processing failed bookingAttemptId={}", queued.bookingAttemptId(), failure);
            return ProcessingOutcome.complete(failed(queued.bookingAttemptId()));
        }
    }

    private void requeueOrComplete(LocalQueuedBooking queued) {
        long now = System.nanoTime();
        // Transient DB pressure should not erase a 202-accepted queue entry.
        // Retry within a bounded age/count budget, then expose a retryable status.
        if (!canRetry(queued, now)) {
            waitingRoom.complete(queued.bookingAttemptId(), unavailable(queued.bookingAttemptId()));
            return;
        }
        LocalQueuedBooking retry = queued.retryAfter(now, retryBackoffNanos(queued));
        if (!waitingRoom.requeue(retry)) {
            waitingRoom.complete(queued.bookingAttemptId(), unavailable(queued.bookingAttemptId()));
        }
    }

    private boolean canRetry(LocalQueuedBooking queued, long nowNanos) {
        if (queued.attemptCount() >= Math.max(0, properties.localQueue().maxRetryAttempts())) {
            return false;
        }
        Duration maxAge = properties.localQueue().maxRetryAge();
        return maxAge == null
                || maxAge.isZero()
                || maxAge.isNegative()
                || nowNanos - queued.firstQueuedAtNanos() <= maxAge.toNanos();
    }

    private long retryBackoffNanos(LocalQueuedBooking queued) {
        Duration backoff = properties.localQueue().retryBackoff();
        if (backoff == null || backoff.isNegative()) {
            return 0;
        }
        return backoff.multipliedBy((long) queued.attemptCount() + 1L).toNanos();
    }

    private BookingResult unavailable(String attemptId) {
        return new BookingResult(
                503,
                "LOCAL_QUEUE_PROCESSING_UNAVAILABLE",
                attemptId,
                null,
                null,
                null,
                true,
                "POLL_BOOKING_STATUS",
                "Local queued booking could not be processed yet"
        );
    }

    private BookingResult failed(String attemptId) {
        return new BookingResult(
                    500,
                    "LOCAL_QUEUE_PROCESSING_FAILED",
                    attemptId,
                    null,
                    null,
                    null,
                    true,
                    "POLL_BOOKING_STATUS",
                    "Local queued booking failed during processing"
        );
    }

    private record ProcessingOutcome(BookingResult result, boolean shouldRetry) {

        private static ProcessingOutcome complete(BookingResult result) {
            return new ProcessingOutcome(result, false);
        }

        private static ProcessingOutcome retryLater() {
            return new ProcessingOutcome(null, true);
        }
    }
}
