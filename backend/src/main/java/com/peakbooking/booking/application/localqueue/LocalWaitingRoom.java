package com.peakbooking.booking.application.localqueue;

import com.peakbooking.booking.application.dto.BookingCommand;
import com.peakbooking.booking.application.dto.BookingResult;
import com.peakbooking.booking.config.BookingProperties;
import java.time.Duration;
import java.util.Comparator;
import java.util.Optional;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Component;

@Component
public class LocalWaitingRoom {

    private final BookingProperties properties;
    private final BlockingQueue<LocalQueuedBooking> waitingQueue;
    private final ConcurrentMap<String, Entry> entries = new ConcurrentHashMap<>();
    private final AtomicLong sequence = new AtomicLong();
    private final AtomicLong recoveredAtNanos = new AtomicLong();
    private final AtomicLong acceptedInCurrentOutage = new AtomicLong();
    private final AtomicBoolean outageEpisodeActive = new AtomicBoolean();

    public LocalWaitingRoom(BookingProperties properties) {
        this.properties = properties;
        int capacity = Math.max(1, properties.localQueue().capacity());
        this.waitingQueue = new ArrayBlockingQueue<>(capacity);
    }

    public LocalQueueSubmission enqueue(BookingCommand command, String bookingAttemptId, String requestHash) {
        cleanupCompletedResults();
        if (!properties.localQueue().enabled()) {
            return new LocalQueueSubmission(LocalQueueSubmission.Status.DISABLED, bookingAttemptId, 0, null);
        }

        Entry existing = entries.get(bookingAttemptId);
        if (existing != null) {
            return existingSubmission(existing, requestHash);
        }
        if (!tryAcquireOutageBudget()) {
            return new LocalQueueSubmission(LocalQueueSubmission.Status.FULL, bookingAttemptId, 0, null);
        }

        long localSequence = sequence.incrementAndGet();
        LocalQueuedBooking queued = LocalQueuedBooking.initial(
                command,
                bookingAttemptId,
                requestHash,
                localSequence,
                System.nanoTime()
        );
        Entry entry = Entry.queued(queued);
        Entry concurrent = entries.putIfAbsent(bookingAttemptId, entry);
        if (concurrent != null) {
            releaseOutageBudget();
            return existingSubmission(concurrent, requestHash);
        }
        if (!waitingQueue.offer(queued)) {
            entries.remove(bookingAttemptId, entry);
            releaseOutageBudget();
            return new LocalQueueSubmission(LocalQueueSubmission.Status.FULL, bookingAttemptId, 0, null);
        }
        return new LocalQueueSubmission(
                LocalQueueSubmission.Status.ACCEPTED,
                bookingAttemptId,
                positionOf(bookingAttemptId),
                null
        );
    }

    public LocalQueuedBooking dequeue() {
        LocalQueuedBooking queued = waitingQueue.poll();
        if (queued == null) {
            return null;
        }
        long now = System.nanoTime();
        if (queued.nextAttemptAtNanos() > now) {
            if (!waitingQueue.offer(queued)) {
                entries.computeIfPresent(queued.bookingAttemptId(), (ignored, entry) -> entry.markRunning());
                return queued;
            }
            return null;
        }
        entries.computeIfPresent(queued.bookingAttemptId(), (ignored, entry) -> entry.markRunning());
        return queued;
    }

    public boolean requeue(LocalQueuedBooking queued) {
        entries.computeIfPresent(queued.bookingAttemptId(), (ignored, entry) -> entry.markQueued(queued));
        if (waitingQueue.offer(queued)) {
            return true;
        }
        entries.computeIfPresent(queued.bookingAttemptId(), (ignored, entry) -> entry.markRunning());
        return false;
    }

    public void complete(String bookingAttemptId, BookingResult result) {
        entries.computeIfPresent(bookingAttemptId, (ignored, entry) -> entry.markCompleted(result));
    }

    public boolean shouldPreferLocalQueue() {
        cleanupCompletedResults();
        if (!properties.localQueue().enabled()) {
            return false;
        }
        if (activeCount() == 0) {
            clearOutageEpisode();
            return false;
        }
        // After Redis recovers, keep new requests on this local queue briefly
        // so already accepted users are not leapfrogged by fresh Redis traffic.
        long recoveredAt = recoveredAtNanos.get();
        if (recoveredAt == 0) {
            return true;
        }
        Duration drainGrace = properties.localQueue().drainGrace();
        if (drainGrace == null || drainGrace.isZero() || drainGrace.isNegative()) {
            return true;
        }
        return System.nanoTime() - recoveredAt <= drainGrace.toNanos();
    }

    public void markRedisUnavailable() {
        if (outageEpisodeActive.compareAndSet(false, true)) {
            acceptedInCurrentOutage.set(0);
        }
        recoveredAtNanos.set(0);
    }

    public boolean markRedisRecovered() {
        cleanupCompletedResults();
        if (!properties.localQueue().enabled() || activeCount() == 0) {
            clearOutageEpisode();
            return false;
        }
        long now = System.nanoTime();
        recoveredAtNanos.compareAndSet(0, now);
        return true;
    }

    public Optional<BookingResult> status(String bookingAttemptId) {
        cleanupCompletedResults();
        Entry entry = entries.get(bookingAttemptId);
        if (entry == null) {
            return Optional.empty();
        }
        if (entry.state == State.COMPLETED && entry.completedResult != null) {
            return Optional.of(entry.completedResult);
        }
        String businessCode = entry.state == State.RUNNING ? "LOCAL_QUEUE_PROCESSING" : BookingResult.LOCAL_QUEUE_ACCEPTED;
        String message = entry.state == State.RUNNING
                ? "Local queued booking is being processed"
                : "Accepted in local waiting room. Approximate local position: " + positionOf(bookingAttemptId);
        return Optional.of(new BookingResult(
                202,
                businessCode,
                bookingAttemptId,
                null,
                null,
                null,
                true,
                "POLL_BOOKING_STATUS",
                message
        ));
    }

    public int queuedCount() {
        return waitingQueue.size();
    }

    public int activeCount() {
        cleanupCompletedResults();
        int active = (int) entries.values().stream()
                .filter(entry -> entry.state == State.QUEUED || entry.state == State.RUNNING)
                .count();
        if (active == 0) {
            clearOutageEpisode();
        }
        return active;
    }

    private LocalQueueSubmission existingSubmission(Entry existing, String requestHash) {
        if (!existing.queued.requestHash().equals(requestHash)) {
            return new LocalQueueSubmission(
                    LocalQueueSubmission.Status.CONFLICT,
                    existing.queued.bookingAttemptId(),
                    0,
                    null
            );
        }
        if (existing.state == State.COMPLETED && existing.completedResult != null) {
            return new LocalQueueSubmission(
                    LocalQueueSubmission.Status.ALREADY_COMPLETED,
                    existing.queued.bookingAttemptId(),
                    0,
                    existing.completedResult
            );
        }
        return new LocalQueueSubmission(
                LocalQueueSubmission.Status.ALREADY_ACCEPTED,
                existing.queued.bookingAttemptId(),
                positionOf(existing.queued.bookingAttemptId()),
                null
        );
    }

    private int positionOf(String bookingAttemptId) {
        Entry target = entries.get(bookingAttemptId);
        if (target == null) {
            return 0;
        }
        return (int) entries.values().stream()
                .filter(entry -> entry.state == State.QUEUED || entry.state == State.RUNNING)
                .filter(entry -> entry.queued.localSequence() <= target.queued.localSequence())
                .sorted(Comparator.comparingLong(entry -> entry.queued.localSequence()))
                .count();
    }

    private boolean tryAcquireOutageBudget() {
        int budget = Math.max(1, properties.localQueue().maxAcceptedPerOutage());
        long accepted = acceptedInCurrentOutage.incrementAndGet();
        return accepted <= budget;
    }

    private void releaseOutageBudget() {
        acceptedInCurrentOutage.updateAndGet(current -> Math.max(0, current - 1));
    }

    private void clearOutageEpisode() {
        recoveredAtNanos.set(0);
        acceptedInCurrentOutage.set(0);
        outageEpisodeActive.set(false);
    }

    private void cleanupCompletedResults() {
        Duration retention = properties.localQueue().resultRetention();
        if (retention == null || retention.isNegative() || retention.isZero()) {
            return;
        }
        long deadline = System.nanoTime() - retention.toNanos();
        entries.entrySet().removeIf(entry ->
                entry.getValue().state == State.COMPLETED
                        && entry.getValue().completedAtNanos > 0
                        && entry.getValue().completedAtNanos < deadline
        );
    }

    private enum State {
        QUEUED,
        RUNNING,
        COMPLETED
    }

    private static final class Entry {
        private final LocalQueuedBooking queued;
        private final State state;
        private final BookingResult completedResult;
        private final long completedAtNanos;

        private Entry(
                LocalQueuedBooking queued,
                State state,
                BookingResult completedResult,
                long completedAtNanos
        ) {
            this.queued = queued;
            this.state = state;
            this.completedResult = completedResult;
            this.completedAtNanos = completedAtNanos;
        }

        private static Entry queued(LocalQueuedBooking queued) {
            return new Entry(queued, State.QUEUED, null, 0);
        }

        private Entry markRunning() {
            if (state == State.COMPLETED) {
                return this;
            }
            return new Entry(queued, State.RUNNING, null, 0);
        }

        private Entry markQueued(LocalQueuedBooking nextQueued) {
            if (state == State.COMPLETED) {
                return this;
            }
            return new Entry(nextQueued, State.QUEUED, null, 0);
        }

        private Entry markCompleted(BookingResult result) {
            return new Entry(queued, State.COMPLETED, result, System.nanoTime());
        }
    }
}
