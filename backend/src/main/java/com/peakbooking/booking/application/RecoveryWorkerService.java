package com.peakbooking.booking.application;

import com.peakbooking.booking.config.BookingProperties;
import com.peakbooking.booking.domain.ReservationStatus;
import com.peakbooking.booking.infrastructure.jdbc.BookingJdbcRepository;
import com.peakbooking.booking.infrastructure.jdbc.PaymentAttemptRecord;
import com.peakbooking.booking.infrastructure.jdbc.RecoveryClaim;
import com.peakbooking.booking.infrastructure.jdbc.ReservationRecord;
import com.peakbooking.booking.payment.PaymentProvider;
import com.peakbooking.booking.payment.PaymentStatus;
import com.peakbooking.booking.payment.PaymentStatusResult;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class RecoveryWorkerService {

    private final BookingProperties properties;
    private final BookingJdbcRepository repository;
    private final PaymentProvider paymentProvider;
    private final Clock clock;
    private final TransactionTemplate transactionTemplate;

    public RecoveryWorkerService(
            BookingProperties properties,
            BookingJdbcRepository repository,
            PaymentProvider paymentProvider,
            Clock clock,
            TransactionTemplate transactionTemplate
    ) {
        this.properties = properties;
        this.repository = repository;
        this.paymentProvider = paymentProvider;
        this.clock = clock;
        this.transactionTemplate = transactionTemplate;
    }

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
        transactionTemplate.execute(status -> {
            repository.markManualReviewAfterWindow(LocalDateTime.now(clock));
            return null;
        });
        return claims.size();
    }

    public void releaseExpiredHeld(long reservationId) {
        transactionTemplate.execute(status -> {
            releaseExpiredHeldInTransaction(reservationId);
            return null;
        });
    }

    private void releaseExpiredHeldInTransaction(long reservationId) {
        ReservationRecord reservation = repository.findReservationForUpdate(reservationId).orElseThrow();
        if (reservation.status() == ReservationStatus.HELD
                && reservation.holdExpiresAt() != null
                && !reservation.holdExpiresAt().isAfter(LocalDateTime.now(clock))) {
            repository.releaseReservation(reservation, "HELD_EXPIRED", LocalDateTime.now(clock));
            repository.releasePoints(reservation.bookingAttemptId());
        }
    }

    private void recoverClaim(RecoveryClaim claim) {
        ReservationRecord reservation = claim.reservation();
        if (reservation.status() == ReservationStatus.HELD) {
            transactionTemplate.execute(status -> {
                if (repository.leaseTokenMatches(reservation.bookingAttemptId(), claim.leaseToken())) {
                    releaseExpiredHeldInTransaction(reservation.id());
                    completeReleasedReplay(reservation, "HELD_EXPIRED");
                }
                return null;
            });
            return;
        }
        if (reservation.status() != ReservationStatus.PAYMENT_UNKNOWN) {
            return;
        }

        PaymentAttemptRecord payment = claim.paymentAttempt();
        PaymentStatusResult paymentStatus = payment.providerPaymentId() == null
                ? PaymentStatusResult.unknown("NO_PROVIDER_PAYMENT_ID")
                : paymentProvider.query(payment.providerPaymentId());

        boolean releaseAfterDeadline = reservation.unknownInventoryDeadlineAt() != null
                && !reservation.unknownInventoryDeadlineAt().isAfter(LocalDateTime.now(clock));
        if (releaseAfterDeadline) {
            transactionTemplate.execute(status -> {
                if (repository.leaseTokenMatches(reservation.bookingAttemptId(), claim.leaseToken())) {
                    releaseUnknownAfterDeadline(reservation, payment, paymentStatus);
                    completeReleasedReplay(reservation, "PAYMENT_UNKNOWN_DEADLINE_EXPIRED");
                }
                return null;
            });
            if (paymentStatus.status() == PaymentStatus.APPROVED && payment.providerPaymentId() != null) {
                PaymentStatusResult cancel = paymentProvider.cancel(
                        payment.providerPaymentId(),
                        "reservation released after deadline"
                );
                transactionTemplate.execute(status -> {
                    if (repository.leaseTokenMatches(reservation.bookingAttemptId(), claim.leaseToken())
                            && cancel.status() == PaymentStatus.CANCELLED) {
                        repository.markCancelledAfterRelease(reservation.bookingAttemptId());
                    }
                    return null;
                });
            }
            return;
        }

        transactionTemplate.execute(status -> {
            if (repository.leaseTokenMatches(reservation.bookingAttemptId(), claim.leaseToken())) {
                applyPaymentStatusBeforeDeadline(reservation, payment, paymentStatus);
            }
            return null;
        });
    }

    private void applyPaymentStatusBeforeDeadline(
            ReservationRecord reservation,
            PaymentAttemptRecord payment,
            PaymentStatusResult status
    ) {
        LocalDateTime now = LocalDateTime.now(clock);
        if (status.status() == PaymentStatus.APPROVED) {
            if (repository.confirmReservation(reservation, now)) {
                repository.capturePoints(reservation.bookingAttemptId());
                repository.markPaymentConfirmed(reservation.bookingAttemptId(), payment.providerPaymentId());
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
        }
    }

    public void releaseUnknownAfterDeadline(
            ReservationRecord reservation,
            PaymentAttemptRecord payment,
            PaymentStatusResult status
    ) {
        ReservationRecord locked = repository.findReservationForUpdate(reservation.id()).orElseThrow();
        if (locked.status() != ReservationStatus.PAYMENT_UNKNOWN) {
            return;
        }
        repository.releaseReservation(locked, "PAYMENT_UNKNOWN_DEADLINE_EXPIRED", LocalDateTime.now(clock));
        repository.releasePoints(locked.bookingAttemptId());
        repository.markReconcilingAfterRelease(locked.bookingAttemptId());

        if (status.status() == PaymentStatus.APPROVED && payment.providerPaymentId() != null) {
            repository.markLateSuccessCancelPending(locked.bookingAttemptId());
        }
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
}
