package com.peakbooking.booking.application;

import com.peakbooking.booking.config.BookingProperties;
import com.peakbooking.booking.domain.PaymentAttemptStatus;
import com.peakbooking.booking.domain.ReservationStatus;
import com.peakbooking.booking.infrastructure.jpa.BookingJpaRepository;
import com.peakbooking.booking.infrastructure.persistence.AdmissionRecord;
import com.peakbooking.booking.infrastructure.persistence.PaymentAttemptRecord;
import com.peakbooking.booking.infrastructure.persistence.RecoveryClaim;
import com.peakbooking.booking.infrastructure.persistence.ReservationRecord;
import com.peakbooking.booking.payment.PaymentCallGuard;
import com.peakbooking.booking.payment.PaymentStatus;
import com.peakbooking.booking.payment.PaymentStatusResult;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
@RequiredArgsConstructor
public class RecoveryWorkerService {

    private final BookingProperties properties;
    private final BookingJpaRepository repository;
    private final BookingAdmissionService admissionService;
    private final PaymentCallGuard paymentCallGuard;
    private final Clock clock;
    private final TransactionTemplate transactionTemplate;

    public int recoverDueReservations() {
        LocalDateTime now = LocalDateTime.now(clock);
        String leaseToken = UUID.randomUUID().toString();
        List<RecoveryClaim> claims = transactionTemplate.execute(status -> repository.claimDueRecoveries(
                now,
                "was-local",
                leaseToken,
                now.plus(properties.recovery().leaseTimeout()),
                properties.recovery().batchSize()
        ));
        if (claims == null) {
            return 0;
        }
        for (RecoveryClaim claim : claims) {
            recoverClaim(claim);
        }
        List<AdmissionRecord> expiredWaitingCandidates = transactionTemplate.execute(status ->
                repository.expireWaitingCandidates(LocalDateTime.now(clock), properties.recovery().batchSize())
        );
        if (expiredWaitingCandidates != null) {
            expiredWaitingCandidates.forEach(admission ->
                    releaseActiveCandidateSlot(admission, "WAITING_EXPIRED")
            );
        }
        transactionTemplate.execute(status -> {
            repository.markManualReviewAfterWindow(LocalDateTime.now(clock));
            return null;
        });
        return claims.size() + (expiredWaitingCandidates == null ? 0 : expiredWaitingCandidates.size());
    }

    public void releaseExpiredHeld(long reservationId) {
        ReservationRecord released = transactionTemplate.execute(status -> releaseExpiredHeldInTransaction(reservationId));
        if (released != null) {
            releaseActiveCandidateSlot(released, "HELD_EXPIRED");
        }
    }

    private ReservationRecord releaseExpiredHeldInTransaction(long reservationId) {
        ReservationRecord reservation = repository.findReservationForUpdate(reservationId).orElseThrow();
        if (reservation.status() == ReservationStatus.HELD
                && reservation.holdExpiresAt() != null
                && !reservation.holdExpiresAt().isAfter(LocalDateTime.now(clock))) {
            repository.releaseReservation(reservation, "HELD_EXPIRED", LocalDateTime.now(clock));
            repository.releasePoints(reservation.bookingAttemptId());
            return reservation;
        }
        return null;
    }

    private void recoverClaim(RecoveryClaim claim) {
        ReservationRecord reservation = claim.reservation();
        if (claim.paymentAttempt().status() == PaymentAttemptStatus.RECONCILING_AFTER_RELEASE
                || claim.paymentAttempt().status() == PaymentAttemptStatus.LATE_SUCCESS_CANCEL_PENDING) {
            recoverReleasedPayment(claim);
            return;
        }
        if (reservation.status() == ReservationStatus.HELD) {
            recoverHeld(claim);
            return;
        }
        if (reservation.status() != ReservationStatus.PAYMENT_UNKNOWN) {
            return;
        }

        boolean releaseAfterDeadline = reservation.unknownInventoryDeadlineAt() != null
                && !reservation.unknownInventoryDeadlineAt().isAfter(LocalDateTime.now(clock));
        if (releaseAfterDeadline) {
            ReservationRecord released = transactionTemplate.execute(status -> {
                if (leaseTokenMatches(reservation.bookingAttemptId(), claim.leaseToken())) {
                    releaseUnknownAfterDeadline(reservation);
                    completeReleasedReplay(reservation, "PAYMENT_UNKNOWN_DEADLINE_EXPIRED");
                    return reservation;
                }
                return null;
            });
            if (released != null) {
                releaseActiveCandidateSlot(released, "PAYMENT_UNKNOWN_DEADLINE_EXPIRED");
            }
            PaymentAttemptRecord payment = claim.paymentAttempt();
            PaymentStatusResult paymentStatus = queryPayment(payment);
            if (paymentStatus.status() == PaymentStatus.APPROVED) {
                PaymentStatusResult cancel = cancelPayment(payment, paymentStatus, "reservation released after deadline");
                transactionTemplate.execute(status -> {
                    if (leaseTokenMatches(reservation.bookingAttemptId(), claim.leaseToken())) {
                        if (cancel.status() == PaymentStatus.CANCELLED) {
                            repository.markCancelledAfterRelease(reservation.bookingAttemptId());
                        } else {
                            repository.markLateSuccessCancelPending(reservation.bookingAttemptId(), LocalDateTime.now(clock));
                        }
                    }
                    return null;
                });
            }
            return;
        }

        PaymentAttemptRecord payment = claim.paymentAttempt();
        PaymentStatusResult paymentStatus = queryPayment(payment);
        ReservationRecord released = transactionTemplate.execute(status -> {
            if (leaseTokenMatches(reservation.bookingAttemptId(), claim.leaseToken())) {
                return applyPaymentStatusBeforeDeadline(reservation, payment, paymentStatus);
            }
            return null;
        });
        if (released != null) {
            releaseActiveCandidateSlot(released, paymentStatus.status().name());
        }
    }

    private void recoverHeld(RecoveryClaim claim) {
        ReservationRecord reservation = claim.reservation();
        PaymentAttemptRecord payment = claim.paymentAttempt();
        if (payment.status() == PaymentAttemptStatus.REQUESTED && payment.confirmStartedAt() == null) {
            ReservationRecord released = transactionTemplate.execute(status -> {
                if (leaseTokenMatches(reservation.bookingAttemptId(), claim.leaseToken())) {
                    ReservationRecord locked = repository.findReservationForUpdate(reservation.id()).orElseThrow();
                    if (locked.status() == ReservationStatus.HELD) {
                        repository.releaseReservation(
                                locked,
                                "PAYMENT_CONFIRM_NOT_STARTED",
                                LocalDateTime.now(clock)
                        );
                        repository.releasePoints(locked.bookingAttemptId());
                        repository.markPaymentFailed(locked.bookingAttemptId(), "PAYMENT_CONFIRM_NOT_STARTED");
                        completeFailedReplay(locked, "PAYMENT_CONFIRM_NOT_STARTED");
                        return locked;
                    }
                }
                return null;
            });
            if (released != null) {
                releaseActiveCandidateSlot(released, "PAYMENT_CONFIRM_NOT_STARTED");
            }
            return;
        }
        boolean pgMayHaveBeenCalled = payment.status() == PaymentAttemptStatus.CONFIRMING
                || payment.confirmStartedAt() != null;
        if (!pgMayHaveBeenCalled) {
            ReservationRecord released = transactionTemplate.execute(status -> {
                if (leaseTokenMatches(reservation.bookingAttemptId(), claim.leaseToken())) {
                    ReservationRecord releasedReservation = releaseExpiredHeldInTransaction(reservation.id());
                    if (releasedReservation != null) {
                        completeReleasedReplay(releasedReservation, "HELD_EXPIRED");
                    }
                    return releasedReservation;
                }
                return null;
            });
            if (released != null) {
                releaseActiveCandidateSlot(released, "HELD_EXPIRED");
            }
            return;
        }

        LocalDateTime now = LocalDateTime.now(clock);
        boolean afterDeadline = reservation.holdExpiresAt() == null || !reservation.holdExpiresAt().isAfter(now);
        if (afterDeadline) {
            ReservationRecord released = transactionTemplate.execute(status -> {
                if (leaseTokenMatches(reservation.bookingAttemptId(), claim.leaseToken())) {
                    ReservationRecord locked = repository.findReservationForUpdate(reservation.id()).orElseThrow();
                    if (locked.status() == ReservationStatus.HELD) {
                        repository.releaseReservation(locked, "HELD_CONFIRM_DEADLINE_EXPIRED", LocalDateTime.now(clock));
                        repository.releasePoints(locked.bookingAttemptId());
                        repository.markReconcilingAfterRelease(
                                locked.bookingAttemptId(),
                                LocalDateTime.now(clock),
                                LocalDateTime.now(clock).plus(properties.reconciliationWindow())
                        );
                        completeReleasedReplay(locked, "HELD_CONFIRM_DEADLINE_EXPIRED");
                        return locked;
                    }
                }
                return null;
            });
            if (released != null) {
                releaseActiveCandidateSlot(released, "HELD_CONFIRM_DEADLINE_EXPIRED");
            }
            PaymentStatusResult paymentStatus = queryPayment(payment);
            if (paymentStatus.status() == PaymentStatus.APPROVED) {
                PaymentStatusResult cancel = cancelPayment(payment, paymentStatus, "held reservation released after deadline");
                transactionTemplate.execute(status -> {
                    if (leaseTokenMatches(reservation.bookingAttemptId(), claim.leaseToken())) {
                        if (cancel.status() == PaymentStatus.CANCELLED) {
                            repository.markCancelledAfterRelease(reservation.bookingAttemptId());
                        } else {
                            repository.markLateSuccessCancelPending(reservation.bookingAttemptId(), LocalDateTime.now(clock));
                        }
                    }
                    return null;
                });
            }
            return;
        }

        PaymentStatusResult paymentStatus = queryPayment(payment);
        ReservationRecord released = transactionTemplate.execute(status -> {
            if (!leaseTokenMatches(reservation.bookingAttemptId(), claim.leaseToken())) {
                return null;
            }
            if (paymentStatus.status() == PaymentStatus.APPROVED) {
                return applyPaymentStatusBeforeDeadline(reservation, payment, paymentStatus);
            } else if (paymentStatus.status() == PaymentStatus.FAILED
                    || paymentStatus.status() == PaymentStatus.CANCELLED) {
                return applyPaymentStatusBeforeDeadline(reservation, payment, paymentStatus);
            } else {
                ReservationRecord locked = repository.findReservationForUpdate(reservation.id()).orElseThrow();
                repository.markPaymentUnknown(
                        locked,
                        locked.holdExpiresAt(),
                        LocalDateTime.now(clock),
                        LocalDateTime.now(clock).plus(properties.reconciliationWindow()),
                        paymentStatus.providerPaymentId()
                );
                repository.scheduleNextReconcile(
                        locked.bookingAttemptId(),
                        nextReconcileAt(LocalDateTime.now(clock), locked.holdExpiresAt()),
                        LocalDateTime.now(clock)
                );
            }
            return null;
        });
        if (released != null) {
            releaseActiveCandidateSlot(released, paymentStatus.status().name());
        }
    }

    private ReservationRecord applyPaymentStatusBeforeDeadline(
            ReservationRecord reservation,
            PaymentAttemptRecord payment,
            PaymentStatusResult status
    ) {
        LocalDateTime now = LocalDateTime.now(clock);
        if (status.status() == PaymentStatus.APPROVED) {
            if (repository.confirmReservation(reservation, now)) {
                repository.capturePoints(reservation.bookingAttemptId());
                repository.markPaymentConfirmed(
                        reservation.bookingAttemptId(),
                        status.providerPaymentId() == null ? payment.providerPaymentId() : status.providerPaymentId()
                );
                BookingResult result = new BookingResult(
                        201,
                        "BOOKING_CONFIRMED",
                        reservation.bookingAttemptId(),
                        reservation.id(),
                        "CONFIRMED",
                        "CONFIRMED",
                        false,
                        "NONE",
                        "Booking confirmed"
                );
                repository.completeIdempotencyIfExists(reservation.bookingAttemptId(), result);
            }
        } else if (status.status() == PaymentStatus.FAILED || status.status() == PaymentStatus.CANCELLED) {
            repository.releaseReservation(reservation, status.status().name(), now);
            repository.releasePoints(reservation.bookingAttemptId());
            repository.markPaymentFailed(reservation.bookingAttemptId(), status.status().name());
            completeReleasedReplay(reservation, status.status().name());
            return reservation;
        } else {
            repository.scheduleNextReconcile(
                    reservation.bookingAttemptId(),
                    nextReconcileAt(now, reservation.unknownInventoryDeadlineAt()),
                    now
            );
        }
        return null;
    }

    public void releaseUnknownAfterDeadline(ReservationRecord reservation) {
        ReservationRecord locked = repository.findReservationForUpdate(reservation.id()).orElseThrow();
        if (locked.status() != ReservationStatus.PAYMENT_UNKNOWN) {
            return;
        }
        repository.releaseReservation(locked, "PAYMENT_UNKNOWN_DEADLINE_EXPIRED", LocalDateTime.now(clock));
        repository.releasePoints(locked.bookingAttemptId());
        repository.markReconcilingAfterRelease(
                locked.bookingAttemptId(),
                LocalDateTime.now(clock),
                LocalDateTime.now(clock).plus(properties.reconciliationWindow())
        );
    }

    private void recoverReleasedPayment(RecoveryClaim claim) {
        PaymentAttemptRecord payment = claim.paymentAttempt();
        LocalDateTime now = LocalDateTime.now(clock);
        if (payment.activeReconcileUntil() != null && !payment.activeReconcileUntil().isAfter(now)) {
            return;
        }

        PaymentStatusResult paymentStatus = queryPayment(payment);
        if (paymentStatus.status() == PaymentStatus.APPROVED) {
            PaymentStatusResult cancel = cancelPayment(payment, paymentStatus, "released reservation payment reconciliation");
            transactionTemplate.execute(status -> {
                if (leaseTokenMatches(payment.bookingAttemptId(), claim.leaseToken())) {
                    if (cancel.status() == PaymentStatus.CANCELLED) {
                        repository.markCancelledAfterRelease(payment.bookingAttemptId());
                    } else {
                        repository.markLateSuccessCancelPending(
                                payment.bookingAttemptId(),
                                nextReconcileAt(now, payment.activeReconcileUntil())
                        );
                    }
                }
                return null;
            });
            return;
        }
        if (paymentStatus.status() == PaymentStatus.CANCELLED) {
            transactionTemplate.execute(status -> {
                if (leaseTokenMatches(payment.bookingAttemptId(), claim.leaseToken())) {
                    repository.markCancelledAfterRelease(payment.bookingAttemptId());
                }
                return null;
            });
            return;
        }
        if (paymentStatus.status() == PaymentStatus.FAILED) {
            transactionTemplate.execute(status -> {
                if (leaseTokenMatches(payment.bookingAttemptId(), claim.leaseToken())) {
                    repository.markPaymentFailed(payment.bookingAttemptId(), "RELEASED_PAYMENT_FAILED");
                }
                return null;
            });
            return;
        }

        transactionTemplate.execute(status -> {
            if (leaseTokenMatches(payment.bookingAttemptId(), claim.leaseToken())) {
                repository.scheduleNextReconcile(
                        payment.bookingAttemptId(),
                        nextReconcileAt(now, payment.activeReconcileUntil()),
                        now
                );
            }
            return null;
        });
    }

    private void completeReleasedReplay(ReservationRecord reservation, String reason) {
        BookingResult result = new BookingResult(
                409,
                "RESERVATION_RELEASED",
                reservation.bookingAttemptId(),
                reservation.id(),
                "RELEASED",
                "RECONCILING_AFTER_RELEASE",
                false,
                "NONE",
                reason
        );
        repository.completeIdempotencyIfExists(reservation.bookingAttemptId(), result);
    }

    private void completeFailedReplay(ReservationRecord reservation, String reason) {
        BookingResult result = new BookingResult(
                422,
                reason,
                reservation.bookingAttemptId(),
                reservation.id(),
                "RELEASED",
                "FAILED",
                false,
                "NONE",
                "Payment did not start"
        );
        repository.completeIdempotencyIfExists(reservation.bookingAttemptId(), result);
    }

    private void releaseActiveCandidateSlot(ReservationRecord reservation, String reason) {
        repository.findAdmissionRecord(reservation.admissionId())
                .map(AdmissionRecord::redisSeq)
                .ifPresent(redisSeq -> releaseActiveCandidateSlot(
                        reservation.saleEventId(),
                        reservation.productId(),
                        reservation.userId(),
                        redisSeq,
                        reason
                ));
    }

    private void releaseActiveCandidateSlot(AdmissionRecord admission, String reason) {
        releaseActiveCandidateSlot(
                admission.saleEventId(),
                admission.productId(),
                admission.userId(),
                admission.redisSeq(),
                reason
        );
    }

    private void releaseActiveCandidateSlot(
            long saleEventId,
            long productId,
            long userId,
            Long redisSeq,
            String reason
    ) {
        admissionService.releaseActiveCandidate(
                saleEventId,
                productId,
                userId,
                redisSeq,
                reason
        );
    }

    private boolean leaseTokenMatches(String bookingAttemptId, String leaseToken) {
        return repository.leaseTokenMatches(bookingAttemptId, leaseToken, LocalDateTime.now(clock));
    }

    private PaymentStatusResult queryPayment(PaymentAttemptRecord payment) {
        if (payment.providerPaymentId() != null) {
            return paymentCallGuard.query(payment.providerPaymentId());
        }
        if (payment.providerOrderId() != null) {
            return paymentCallGuard.queryByOrderId(payment.providerOrderId());
        }
        return PaymentStatusResult.unknown("NO_PROVIDER_LOOKUP_KEY");
    }

    private PaymentStatusResult cancelPayment(
            PaymentAttemptRecord payment,
            PaymentStatusResult status,
            String reason
    ) {
        String providerPaymentId = status.providerPaymentId() == null
                ? payment.providerPaymentId()
                : status.providerPaymentId();
        if (providerPaymentId != null) {
            return paymentCallGuard.cancel(providerPaymentId, reason);
        }
        if (payment.providerOrderId() != null) {
            return paymentCallGuard.cancelByOrderId(payment.providerOrderId(), reason);
        }
        return PaymentStatusResult.unknown("NO_PROVIDER_LOOKUP_KEY");
    }

    private LocalDateTime nextReconcileAt(LocalDateTime now, LocalDateTime deadline) {
        LocalDateTime next = now.plus(shortBackoff());
        if (deadline != null && next.isAfter(deadline)) {
            return deadline;
        }
        return next;
    }

    private Duration shortBackoff() {
        Duration fixedDelay = properties.recovery().fixedDelay();
        Duration deadlineFriendly = properties.holdTimeout().dividedBy(2);
        if (deadlineFriendly.isZero() || deadlineFriendly.isNegative()) {
            return fixedDelay;
        }
        return fixedDelay.compareTo(deadlineFriendly) <= 0 ? fixedDelay : deadlineFriendly;
    }
}
