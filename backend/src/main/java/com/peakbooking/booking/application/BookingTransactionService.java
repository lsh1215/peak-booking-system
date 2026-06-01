package com.peakbooking.booking.application;

import com.peakbooking.booking.config.BookingProperties;
import com.peakbooking.booking.domain.BookingErrorCode;
import com.peakbooking.booking.domain.PaymentAttemptStatus;
import com.peakbooking.booking.domain.PaymentMethodType;
import com.peakbooking.booking.domain.AdmissionStatus;
import com.peakbooking.booking.infrastructure.jdbc.BookingJdbcRepository;
import com.peakbooking.booking.infrastructure.jdbc.IdempotencyRecord;
import com.peakbooking.booking.infrastructure.jdbc.AdmissionRecord;
import com.peakbooking.booking.infrastructure.jdbc.ReservationRecord;
import com.peakbooking.common.exception.BusinessException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class BookingTransactionService {

    private final BookingProperties properties;
    private final BookingJdbcRepository repository;
    private final Clock clock;

    public BookingTransactionService(
            BookingProperties properties,
            BookingJdbcRepository repository,
            Clock clock
    ) {
        this.properties = properties;
        this.repository = repository;
        this.clock = clock;
    }

    @Transactional
    public Optional<BookingResult> replayExisting(String bookingAttemptId, String requestHash) {
        Optional<IdempotencyRecord> existing = repository.findIdempotency(bookingAttemptId);
        if (existing.isEmpty()) {
            return Optional.empty();
        }
        IdempotencyRecord record = existing.get();
        if (!record.requestHash().equals(requestHash)) {
            throw new BusinessException(BookingErrorCode.IDEMPOTENCY_CONFLICT);
        }
        if (record.httpStatus() != null && record.responseSnapshot() != null) {
            return Optional.of(new BookingResult(
                    record.httpStatus(),
                    record.businessCode(),
                    bookingAttemptId,
                    record.reservationId(),
                    null,
                    null,
                    false,
                    "REPLAY",
                    "Replayed stored logical response"
            ));
        }
        Optional<ReservationRecord> reservation = repository.findReservationByAttempt(bookingAttemptId);
        if (reservation.isPresent()) {
            ReservationRecord value = reservation.get();
            return Optional.of(new BookingResult(
                    202,
                    "BOOKING_IN_PROGRESS",
                    bookingAttemptId,
                    value.id(),
                    value.status().name(),
                    repository.findPaymentAttempt(bookingAttemptId)
                            .map(payment -> payment.status().name())
                            .orElse(PaymentAttemptStatus.REQUESTED.name()),
                    true,
                    "RETRY_POST_BOOKINGS",
                    "Booking is still being processed"
            ));
        }
        Optional<AdmissionRecord> admission = repository.findAdmissionByAttempt(bookingAttemptId);
        if (admission.isPresent()) {
            AdmissionRecord value = admission.get();
            if (value.status() == AdmissionStatus.WAITING_CANDIDATE
                    && value.waitingExpiresAt() != null
                    && !value.waitingExpiresAt().isAfter(LocalDateTime.now(clock))) {
                repository.markAdmissionWaitingExpired(value.id(), LocalDateTime.now(clock));
                BookingResult result = new BookingResult(
                        409,
                        "WAITING_EXPIRED",
                        bookingAttemptId,
                        null,
                        "WAITING_EXPIRED",
                        null,
                        false,
                        "NONE",
                        "Waiting window expired"
                );
                repository.completeIdempotency(bookingAttemptId, result);
                return Optional.of(result);
            }
            return Optional.empty();
        }
        return Optional.of(new BookingResult(
                202,
                "BOOKING_IN_PROGRESS",
                bookingAttemptId,
                null,
                null,
                null,
                true,
                "RETRY_POST_BOOKINGS",
                "Booking attempt is accepted but not completed"
        ));
    }

    @Transactional(readOnly = true)
    public Optional<com.peakbooking.booking.domain.AdmissionDecision> existingAdmission(String bookingAttemptId) {
        return repository.findAdmissionByAttempt(bookingAttemptId)
                .map(admission -> com.peakbooking.booking.domain.AdmissionDecision.admitted(
                        admission.id(),
                        admission.dbAdmissionSeq(),
                        null,
                        Math.toIntExact(admission.dbAdmissionSeq()),
                        com.peakbooking.booking.domain.GateMode.REDIS
                ));
    }

    @Transactional
    public Optional<BookingResult> startIdempotencyOrReplay(
            String bookingAttemptId,
            String requestHash,
            long admissionId
    ) {
        Optional<IdempotencyRecord> existing = repository.findIdempotency(bookingAttemptId);
        if (existing.isEmpty()) {
            repository.createIdempotency(
                    bookingAttemptId,
                    requestHash,
                    LocalDateTime.now(clock).plus(properties.idempotencyRetention())
            );
            return Optional.empty();
        }

        IdempotencyRecord record = existing.get();
        if (!record.requestHash().equals(requestHash)) {
            throw new BusinessException(BookingErrorCode.IDEMPOTENCY_CONFLICT);
        }
        if (record.httpStatus() != null && record.responseSnapshot() != null) {
            return Optional.of(new BookingResult(
                    record.httpStatus(),
                    record.businessCode(),
                    bookingAttemptId,
                    record.reservationId(),
                    null,
                    null,
                    false,
                    "REPLAY",
                    "Replayed stored logical response"
            ));
        }

        Optional<ReservationRecord> reservation = repository.findReservationByAttempt(bookingAttemptId);
        if (reservation.isPresent()) {
            ReservationRecord value = reservation.get();
            return Optional.of(new BookingResult(
                    202,
                    "BOOKING_IN_PROGRESS",
                    bookingAttemptId,
                    value.id(),
                    value.status().name(),
                    repository.findPaymentAttempt(bookingAttemptId)
                            .map(payment -> payment.status().name())
                            .orElse(PaymentAttemptStatus.REQUESTED.name()),
                    true,
                    "RETRY_POST_BOOKINGS",
                    "Booking is still being processed"
            ));
        }

        Optional<AdmissionRecord> admission = repository.findAdmissionRecord(admissionId);
        if (admission.isPresent()
                && admission.get().status() == AdmissionStatus.WAITING_CANDIDATE
                && admission.get().waitingExpiresAt() != null
                && !admission.get().waitingExpiresAt().isAfter(LocalDateTime.now(clock))) {
            repository.markAdmissionWaitingExpired(admissionId, LocalDateTime.now(clock));
            BookingResult result = new BookingResult(
                    409,
                    "WAITING_EXPIRED",
                    bookingAttemptId,
                    null,
                    "WAITING_EXPIRED",
                    null,
                    false,
                    "NONE",
                    "Waiting window expired"
            );
            repository.completeIdempotency(bookingAttemptId, result);
            return Optional.of(result);
        }

        return Optional.empty();
    }

    @Transactional
    public Optional<Long> createHeldAndPayment(
            long admissionId,
            String bookingAttemptId,
            BookingCommand command
    ) {
        repository.attachAttemptToAdmission(admissionId, bookingAttemptId);
        Optional<AdmissionRecord> admission = repository.findAdmissionRecord(admissionId);
        LocalDateTime now = LocalDateTime.now(clock);
        if (admission.isPresent()
                && admission.get().status() == AdmissionStatus.WAITING_CANDIDATE
                && !repository.isEarliestWaitingCandidate(admissionId, now)) {
            return Optional.empty();
        }
        Optional<Long> reservationId = repository.createHeldReservation(
                admissionId,
                bookingAttemptId,
                command.saleEventId(),
                command.productId(),
                command.userId(),
                now.plus(properties.holdTimeout())
        );
        if (reservationId.isEmpty()) {
            return Optional.empty();
        }
        long pointAmount = command.paymentPlan().amountOf(PaymentMethodType.Y_POINT);
        if (!repository.holdPoints(command.userId(), bookingAttemptId, pointAmount)) {
            ReservationRecord reservation = repository.findReservationForUpdate(reservationId.get()).orElseThrow();
            repository.releaseReservation(reservation, "POINTS_NOT_ENOUGH", LocalDateTime.now(clock));
            throw new BusinessException(BookingErrorCode.POINTS_NOT_ENOUGH);
        }
        repository.createPaymentAttempt(
                bookingAttemptId,
                reservationId.get(),
                primaryMethod(command).name(),
                command.totalAmount() - pointAmount
        );
        return reservationId;
    }

    @Transactional
    public BookingResult confirm(String bookingAttemptId, long reservationId, String providerPaymentId) {
        ReservationRecord reservation = repository.findReservationForUpdate(reservationId).orElseThrow();
        if (!repository.confirmReservation(reservation, LocalDateTime.now(clock))) {
            throw new BusinessException(BookingErrorCode.SOLD_OUT);
        }
        repository.capturePoints(bookingAttemptId);
        repository.markPaymentConfirmed(bookingAttemptId, providerPaymentId);
        BookingResult result = new BookingResult(
                201,
                "BOOKING_CONFIRMED",
                bookingAttemptId,
                reservationId,
                "CONFIRMED",
                "CONFIRMED",
                false,
                "NONE",
                "Booking confirmed"
        );
        repository.completeIdempotency(bookingAttemptId, result);
        return result;
    }

    @Transactional
    public BookingResult fail(String bookingAttemptId, long reservationId, String errorCode) {
        ReservationRecord reservation = repository.findReservationForUpdate(reservationId).orElseThrow();
        repository.releaseReservation(reservation, errorCode, LocalDateTime.now(clock));
        repository.releasePoints(bookingAttemptId);
        repository.markPaymentFailed(bookingAttemptId, errorCode);
        BookingResult result = new BookingResult(
                422,
                "PAYMENT_FAILED",
                bookingAttemptId,
                reservationId,
                "RELEASED",
                "FAILED",
                false,
                "NONE",
                "Payment failed"
        );
        repository.completeIdempotency(bookingAttemptId, result);
        return result;
    }

    @Transactional
    public BookingResult unknown(
            String bookingAttemptId,
            long reservationId,
            String providerPaymentId
    ) {
        ReservationRecord reservation = repository.findReservationForUpdate(reservationId).orElseThrow();
        LocalDateTime now = LocalDateTime.now(clock);
        repository.markPaymentUnknown(
                reservation,
                now.plus(properties.holdTimeout()),
                now,
                now.plus(properties.reconciliationWindow()),
                providerPaymentId
        );
        return new BookingResult(
                202,
                "PAYMENT_UNKNOWN",
                bookingAttemptId,
                reservationId,
                "PAYMENT_UNKNOWN",
                "PAYMENT_UNKNOWN",
                true,
                "RETRY_POST_BOOKINGS",
                "Payment result is being verified"
        );
    }

    @Transactional
    public BookingResult waiting(String bookingAttemptId, long admissionId) {
        LocalDateTime now = LocalDateTime.now(clock);
        repository.markAdmissionWaiting(admissionId, now.plus(properties.waitingTimeout()));
        return new BookingResult(
                202,
                "WAITING_CANDIDATE",
                bookingAttemptId,
                null,
                "WAITING_CANDIDATE",
                null,
                true,
                "RETRY_POST_BOOKINGS",
                "Waiting candidate"
        );
    }

    @Transactional
    public void markManualReviews() {
        repository.markManualReviewAfterWindow(LocalDateTime.now(clock));
    }

    private PaymentMethodType primaryMethod(BookingCommand command) {
        if (command.paymentPlan().has(PaymentMethodType.CREDIT_CARD)) {
            return PaymentMethodType.CREDIT_CARD;
        }
        if (command.paymentPlan().has(PaymentMethodType.Y_PAY)) {
            return PaymentMethodType.Y_PAY;
        }
        return PaymentMethodType.Y_POINT;
    }
}
