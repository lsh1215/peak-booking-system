package com.peakbooking.booking.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.peakbooking.booking.config.BookingProperties;
import com.peakbooking.booking.domain.AdmissionDecision;
import com.peakbooking.booking.domain.BookingErrorCode;
import com.peakbooking.booking.domain.PaymentAttemptStatus;
import com.peakbooking.booking.domain.PaymentMethodType;
import com.peakbooking.booking.domain.AdmissionStatus;
import com.peakbooking.booking.domain.ReservationStatus;
import com.peakbooking.booking.infrastructure.jpa.BookingJpaRepository;
import com.peakbooking.booking.infrastructure.persistence.IdempotencyRecord;
import com.peakbooking.booking.infrastructure.persistence.AdmissionRecord;
import com.peakbooking.booking.infrastructure.persistence.ReservationCreationResult;
import com.peakbooking.booking.infrastructure.persistence.ReservationRecord;
import com.peakbooking.booking.payment.PaymentExecutionComponent;
import com.peakbooking.booking.payment.PaymentExecutionPlan;
import com.peakbooking.common.exception.BusinessException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class BookingTransactionService {

    private final BookingProperties properties;
    private final BookingJpaRepository repository;
    private final Clock clock;
    private final ObjectMapper objectMapper;

    public BookingTransactionService(
            BookingProperties properties,
            BookingJpaRepository repository,
            Clock clock,
            ObjectMapper objectMapper
    ) {
        this.properties = properties;
        this.repository = repository;
        this.clock = clock;
        this.objectMapper = objectMapper;
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
            return Optional.of(storedReplay(record));
        }
        Optional<ReservationRecord> reservation = repository.findReservationByAttempt(bookingAttemptId);
        if (reservation.isPresent()) {
            return Optional.of(currentState(bookingAttemptId, reservation.get()));
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
                .filter(admission -> admission.dbAdmissionSeq() != null)
                .map(admission -> AdmissionDecision.admitted(
                        admission.id(),
                        admission.dbAdmissionSeq(),
                        null,
                        Math.toIntExact(admission.dbAdmissionSeq()),
                        com.peakbooking.booking.domain.GateMode.REDIS
                ));
    }

    @Transactional
    public Optional<BookingResult> canonicalStateForDifferentAttempt(
            long saleEventId,
            long productId,
            long userId,
            String incomingAttemptId
    ) {
        return repository.findAdmissionRecord(saleEventId, productId, userId)
                .filter(admission -> admission.bookingAttemptId() != null)
                .filter(admission -> !admission.bookingAttemptId().equals(incomingAttemptId))
                .map(admission -> canonicalState(admission.bookingAttemptId(), admission));
    }

    @Transactional
    public Optional<BookingResult> canonicalStateForDifferentAttempt(long admissionId, String incomingAttemptId) {
        return repository.findAdmissionRecord(admissionId)
                .filter(admission -> admission.bookingAttemptId() != null)
                .filter(admission -> !admission.bookingAttemptId().equals(incomingAttemptId))
                .map(admission -> canonicalState(admission.bookingAttemptId(), admission));
    }

    @Transactional
    public Optional<BookingResult> startIdempotencyOrReplay(
            String bookingAttemptId,
            String requestHash,
            long admissionId
    ) {
        Optional<AdmissionRecord> admission = repository.findAdmissionRecord(admissionId);
        if (admission.isPresent()
                && admission.get().bookingAttemptId() != null
                && !admission.get().bookingAttemptId().equals(bookingAttemptId)) {
            return Optional.of(canonicalState(admission.get().bookingAttemptId(), admission.get()));
        }

        Optional<IdempotencyRecord> existing = repository.findIdempotency(bookingAttemptId);
        if (existing.isEmpty()) {
            boolean created = repository.createIdempotency(
                    bookingAttemptId,
                    requestHash,
                    LocalDateTime.now(clock).plus(properties.idempotencyRetention())
            );
            if (created) {
                return Optional.empty();
            }
            existing = repository.findIdempotency(bookingAttemptId);
        }

        IdempotencyRecord record = existing.orElseThrow();
        if (!record.requestHash().equals(requestHash)) {
            throw new BusinessException(BookingErrorCode.IDEMPOTENCY_CONFLICT);
        }
        if (record.httpStatus() != null && record.responseSnapshot() != null) {
            return Optional.of(storedReplay(record));
        }

        Optional<ReservationRecord> reservation = repository.findReservationByAttempt(bookingAttemptId);
        if (reservation.isPresent()) {
            return Optional.of(currentState(bookingAttemptId, reservation.get()));
        }

        if (admission.isPresent()) {
            AdmissionRecord value = admission.get();
            if (value.status() == AdmissionStatus.WAITING_CANDIDATE) {
                LocalDateTime now = LocalDateTime.now(clock);
                if (value.waitingExpiresAt() != null && !value.waitingExpiresAt().isAfter(now)) {
                    repository.markAdmissionWaitingExpired(admissionId, now);
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
                if (repository.isEarliestWaitingCandidate(admissionId, now)) {
                    return Optional.empty();
                }
                return Optional.of(new BookingResult(
                        202,
                        "WAITING_CANDIDATE",
                        bookingAttemptId,
                        null,
                        "WAITING_CANDIDATE",
                        null,
                        true,
                        "RETRY_POST_BOOKINGS",
                        "Waiting candidate"
                ));
            }
            if (value.status() != AdmissionStatus.ADMITTED) {
                BookingResult result = terminalAdmissionResult(bookingAttemptId, value);
                repository.completeIdempotencyIfExists(bookingAttemptId, result);
                return Optional.of(result);
            }
            if (value.bookingAttemptId() == null || value.bookingAttemptId().equals(bookingAttemptId)) {
                return Optional.empty();
            }
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

    @Transactional
    public PaymentPreparationResult createHeldAndPayment(
            long admissionId,
            String bookingAttemptId,
            BookingCommand command,
            PaymentExecutionPlan paymentExecutionPlan
    ) {
        repository.attachAttemptToAdmission(admissionId, bookingAttemptId);
        Optional<AdmissionRecord> admission = repository.findAdmissionRecord(admissionId);
        LocalDateTime now = LocalDateTime.now(clock);
        if (admission.isPresent()
                && admission.get().bookingAttemptId() != null
                && !admission.get().bookingAttemptId().equals(bookingAttemptId)) {
            return PaymentPreparationResult.waiting();
        }
        if (admission.isPresent()
                && admission.get().status() != AdmissionStatus.ADMITTED
                && admission.get().status() != AdmissionStatus.WAITING_CANDIDATE) {
            return PaymentPreparationResult.terminalAdmission();
        }
        Optional<ReservationRecord> existingReservation = repository.findReservationByAttempt(bookingAttemptId);
        if (existingReservation.isPresent()) {
            return PaymentPreparationResult.existing(existingReservation.get().id());
        }
        if (admission.isPresent()
                && admission.get().status() == AdmissionStatus.WAITING_CANDIDATE
                && !repository.isEarliestWaitingCandidate(admissionId, now)) {
            return PaymentPreparationResult.waiting();
        }
        Optional<ReservationCreationResult> reservationCreation = repository.createHeldReservation(
                admissionId,
                bookingAttemptId,
                command.saleEventId(),
                command.productId(),
                command.userId(),
                now.plus(properties.holdTimeout())
        );
        if (reservationCreation.isEmpty()) {
            Optional<ReservationRecord> reservationCreatedByConcurrentRequest =
                    repository.findReservationByAttempt(bookingAttemptId);
            if (reservationCreatedByConcurrentRequest.isPresent()) {
                return PaymentPreparationResult.existing(reservationCreatedByConcurrentRequest.get().id());
            }
            return PaymentPreparationResult.waiting();
        }
        if (!reservationCreation.get().created()) {
            return PaymentPreparationResult.existing(reservationCreation.get().reservationId());
        }
        long reservationId = reservationCreation.get().reservationId();
        long pointAmount = paymentExecutionPlan.pointAmount();
        if (!repository.holdPoints(command.userId(), bookingAttemptId, pointAmount)) {
            ReservationRecord reservation = repository.findReservationForUpdate(reservationId).orElseThrow();
            repository.releaseReservation(reservation, "POINTS_NOT_ENOUGH", LocalDateTime.now(clock));
            BookingResult result = new BookingResult(
                    422,
                    "POINTS_NOT_ENOUGH",
                    bookingAttemptId,
                    reservationId,
                    "RELEASED",
                    "FAILED",
                    false,
                    "NONE",
                    "Y points are not enough"
            );
            repository.completeIdempotency(bookingAttemptId, result);
            return PaymentPreparationResult.failed(result);
        }
        PaymentExecutionComponent paymentComponent = paymentExecutionPlan.externalComponent()
                .orElseGet(() -> PaymentExecutionComponent.internalLedger(PaymentMethodType.Y_POINT, pointAmount));
        LocalDateTime holdExpiresAt = now.plus(properties.holdTimeout());
        boolean paymentCreated = repository.createPaymentAttempt(
                bookingAttemptId,
                reservationId,
                paymentComponent.methodType().name(),
                paymentComponent.amount(),
                providerOrderId(paymentComponent, bookingAttemptId),
                nextReconcileAt(now, holdExpiresAt)
        );
        if (!paymentCreated) {
            return PaymentPreparationResult.existing(reservationId);
        }
        return PaymentPreparationResult.created(reservationId, paymentExecutionPlan.externalComponent().orElse(null));
    }

    @Transactional
    public boolean markPaymentConfirming(String bookingAttemptId) {
        ReservationRecord reservation = repository.findReservationByAttempt(bookingAttemptId).orElseThrow();
        LocalDateTime now = LocalDateTime.now(clock);
        LocalDateTime nextReconcileAt = now
                .plus(properties.payment().callTimeout())
                .plus(properties.payment().confirmRecoveryGrace());
        if (reservation.holdExpiresAt() != null && reservation.holdExpiresAt().isBefore(nextReconcileAt)) {
            nextReconcileAt = reservation.holdExpiresAt();
        }
        return repository.markPaymentConfirming(bookingAttemptId, now, nextReconcileAt);
    }

    @Transactional
    public BookingResult currentState(String bookingAttemptId) {
        Optional<IdempotencyRecord> idempotency = repository.findIdempotency(bookingAttemptId);
        if (idempotency.isPresent()
                && idempotency.get().httpStatus() != null
                && idempotency.get().responseSnapshot() != null) {
            return storedReplay(idempotency.get());
        }
        Optional<ReservationRecord> reservation = repository.findReservationByAttempt(bookingAttemptId);
        if (reservation.isPresent()) {
            return currentState(bookingAttemptId, reservation.get());
        }
        return new BookingResult(
                202,
                "BOOKING_IN_PROGRESS",
                bookingAttemptId,
                null,
                null,
                null,
                true,
                "RETRY_POST_BOOKINGS",
                "Booking attempt is accepted but not completed"
        );
    }

    @Transactional
    public BookingResult terminalAdmission(String bookingAttemptId, long admissionId) {
        AdmissionRecord admission = repository.findAdmissionRecord(admissionId).orElseThrow();
        BookingResult result = terminalAdmissionResult(bookingAttemptId, admission);
        repository.completeIdempotencyIfExists(bookingAttemptId, result);
        return result;
    }

    @Transactional
    public BookingResult confirm(String bookingAttemptId, long reservationId, String providerPaymentId) {
        ReservationRecord reservation = repository.findReservationForUpdate(reservationId).orElseThrow();
        if (!repository.confirmReservation(reservation, LocalDateTime.now(clock))) {
            ReservationRecord latest = repository.findReservationForUpdate(reservationId).orElseThrow();
            if (latest.status() == ReservationStatus.CONFIRMED
                    && latest.bookingAttemptId().equals(bookingAttemptId)) {
                BookingResult result = confirmedResult(bookingAttemptId, reservationId);
                repository.completeIdempotencyIfExists(bookingAttemptId, result);
                return result;
            }
            throw new BusinessException(BookingErrorCode.SOLD_OUT);
        }
        repository.capturePoints(bookingAttemptId);
        repository.markPaymentConfirmed(bookingAttemptId, providerPaymentId);
        BookingResult result = confirmedResult(bookingAttemptId, reservationId);
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
        Optional<AdmissionRecord> current = repository.findAdmissionRecord(admissionId);
        if (current.isPresent()
                && current.get().status() != AdmissionStatus.ADMITTED
                && current.get().status() != AdmissionStatus.WAITING_CANDIDATE) {
            return terminalAdmissionResult(bookingAttemptId, current.get());
        }
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

    private String providerOrderId(PaymentExecutionComponent component, String bookingAttemptId) {
        if (component.providerOrderId() != null) {
            return component.providerOrderId();
        }
        return bookingAttemptId + ":" + component.methodType().name();
    }

    private LocalDateTime nextReconcileAt(LocalDateTime now, LocalDateTime holdExpiresAt) {
        LocalDateTime candidate = now
                .plus(properties.payment().callTimeout())
                .plus(properties.payment().confirmRecoveryGrace());
        if (holdExpiresAt != null && holdExpiresAt.isBefore(candidate)) {
            return holdExpiresAt;
        }
        return candidate;
    }

    private BookingResult storedReplay(IdempotencyRecord record) {
        try {
            return objectMapper.readValue(record.responseSnapshot(), BookingResult.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to deserialize booking result snapshot", e);
        }
    }

    private BookingResult currentState(String bookingAttemptId, ReservationRecord reservation) {
        PaymentAttemptStatus paymentStatus = repository.findPaymentAttempt(bookingAttemptId)
                .map(payment -> payment.status())
                .orElse(PaymentAttemptStatus.REQUESTED);
        String businessCode = reservation.status().name();
        if (paymentStatus == PaymentAttemptStatus.PAYMENT_UNKNOWN) {
            businessCode = "PAYMENT_UNKNOWN";
        } else if (reservation.status().name().equals("HELD")) {
            businessCode = "BOOKING_IN_PROGRESS";
        }
        return new BookingResult(
                202,
                businessCode,
                bookingAttemptId,
                reservation.id(),
                reservation.status().name(),
                paymentStatus.name(),
                true,
                "RETRY_POST_BOOKINGS",
                "Booking is still being processed"
        );
    }

    private BookingResult canonicalState(String canonicalAttemptId, AdmissionRecord admission) {
        if (admission.status() == AdmissionStatus.WAITING_CANDIDATE
                && admission.waitingExpiresAt() != null
                && !admission.waitingExpiresAt().isAfter(LocalDateTime.now(clock))) {
            repository.markAdmissionWaitingExpired(admission.id(), LocalDateTime.now(clock));
            BookingResult result = new BookingResult(
                    409,
                    "WAITING_EXPIRED",
                    canonicalAttemptId,
                    null,
                    "WAITING_EXPIRED",
                    null,
                    false,
                    "NONE",
                    "Waiting window expired"
            );
            repository.completeIdempotencyIfExists(canonicalAttemptId, result);
            return result;
        }
        Optional<IdempotencyRecord> idempotency = repository.findIdempotency(canonicalAttemptId);
        if (idempotency.isPresent()
                && idempotency.get().httpStatus() != null
                && idempotency.get().responseSnapshot() != null) {
            return storedReplay(idempotency.get());
        }
        Optional<ReservationRecord> reservation = repository.findReservationByAttempt(canonicalAttemptId);
        if (reservation.isPresent()) {
            return currentState(canonicalAttemptId, reservation.get());
        }
        if (admission.status() != AdmissionStatus.ADMITTED
                && admission.status() != AdmissionStatus.WAITING_CANDIDATE) {
            return terminalAdmissionResult(canonicalAttemptId, admission);
        }
        return new BookingResult(
                202,
                "BOOKING_IN_PROGRESS",
                canonicalAttemptId,
                null,
                admission.status().name(),
                null,
                true,
                "RETRY_POST_BOOKINGS",
                "Canonical booking attempt is still being processed"
        );
    }

    private BookingResult confirmedResult(String bookingAttemptId, long reservationId) {
        return new BookingResult(
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
    }

    private BookingResult terminalAdmissionResult(String bookingAttemptId, AdmissionRecord admission) {
        if (admission.status() == AdmissionStatus.SUCCEEDED) {
            return new BookingResult(
                    409,
                    "ADMISSION_REJECTED",
                    bookingAttemptId,
                    null,
                    "CONFIRMED",
                    null,
                    false,
                    "NONE",
                    "Admission is already completed"
            );
        }
        return new BookingResult(
                409,
                admission.status().name(),
                bookingAttemptId,
                null,
                admission.status().name(),
                null,
                false,
                "NONE",
                "Admission is terminal"
        );
    }
}
