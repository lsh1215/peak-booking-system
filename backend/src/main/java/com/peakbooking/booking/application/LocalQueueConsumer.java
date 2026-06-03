package com.peakbooking.booking.application;

import com.peakbooking.booking.config.BookingProperties;
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

    int drainOnce() {
        int processed = 0;
        int batchSize = Math.max(1, properties.localQueue().workerBatchSize());
        for (int i = 0; i < batchSize; i++) {
            LocalQueuedBooking queued = waitingRoom.dequeue();
            if (queued == null) {
                return processed;
            }
            BookingResult result = process(queued);
            waitingRoom.complete(queued.bookingAttemptId(), result);
            processed++;
        }
        return processed;
    }

    private BookingResult process(LocalQueuedBooking queued) {
        try {
            return bookingApplicationService.processLocalQueued(queued.command());
        } catch (CannotCreateTransactionException
                 | DataAccessResourceFailureException
                 | QueryTimeoutException failure) {
            log.warn(
                    "Local queue processing skipped because dependency is busy bookingAttemptId={}",
                    queued.bookingAttemptId(),
                    failure
            );
            return new BookingResult(
                    503,
                    "LOCAL_QUEUE_PROCESSING_UNAVAILABLE",
                    queued.bookingAttemptId(),
                    null,
                    null,
                    null,
                    true,
                    "POLL_BOOKING_STATUS",
                    "Local queued booking could not be processed yet"
            );
        } catch (RuntimeException failure) {
            log.warn("Local queue processing failed bookingAttemptId={}", queued.bookingAttemptId(), failure);
            return new BookingResult(
                    500,
                    "LOCAL_QUEUE_PROCESSING_FAILED",
                    queued.bookingAttemptId(),
                    null,
                    null,
                    null,
                    true,
                    "POLL_BOOKING_STATUS",
                    "Local queued booking failed during processing"
            );
        }
    }
}
