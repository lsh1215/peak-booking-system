package com.peakbooking.booking.infrastructure.jpa.repository;

import com.peakbooking.booking.domain.PaymentAttemptStatus;
import com.peakbooking.booking.infrastructure.jpa.entity.PaymentAttemptEntity;
import java.time.LocalDateTime;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PaymentAttemptJpaRepository extends JpaRepository<PaymentAttemptEntity, Long> {

    Optional<PaymentAttemptEntity> findByBookingAttemptId(String bookingAttemptId);

    @Modifying
    @Query("""
            UPDATE PaymentAttemptEntity payment
            SET payment.status = :status,
                payment.confirmStartedAt = COALESCE(payment.confirmStartedAt, :now),
                payment.nextReconcileAt = :nextReconcileAt,
                payment.leaseUntil = NULL,
                payment.leaseToken = NULL,
                payment.leaseOwner = NULL
            WHERE payment.bookingAttemptId = :bookingAttemptId
              AND payment.status = com.peakbooking.booking.domain.PaymentAttemptStatus.REQUESTED
              AND (payment.leaseUntil IS NULL OR payment.leaseUntil < :now)
            """)
    int markConfirming(
            @Param("bookingAttemptId") String bookingAttemptId,
            @Param("status") PaymentAttemptStatus status,
            @Param("now") LocalDateTime now,
            @Param("nextReconcileAt") LocalDateTime nextReconcileAt
    );

    @Modifying
    @Query("""
            UPDATE PaymentAttemptEntity payment
            SET payment.status = :status,
                payment.providerPaymentId = COALESCE(:providerPaymentId, payment.providerPaymentId),
                payment.firstUnknownAt = :firstUnknownAt,
                payment.activeReconcileUntil = :activeReconcileUntil,
                payment.nextReconcileAt = :nextReconcileAt
            WHERE payment.bookingAttemptId = :bookingAttemptId
            """)
    int markUnknown(
            @Param("bookingAttemptId") String bookingAttemptId,
            @Param("status") PaymentAttemptStatus status,
            @Param("providerPaymentId") String providerPaymentId,
            @Param("firstUnknownAt") LocalDateTime firstUnknownAt,
            @Param("activeReconcileUntil") LocalDateTime activeReconcileUntil,
            @Param("nextReconcileAt") LocalDateTime nextReconcileAt
    );

    @Modifying
    @Query("""
            UPDATE PaymentAttemptEntity payment
            SET payment.status = :status,
                payment.providerPaymentId = COALESCE(:providerPaymentId, payment.providerPaymentId)
            WHERE payment.bookingAttemptId = :bookingAttemptId
            """)
    int markConfirmed(
            @Param("bookingAttemptId") String bookingAttemptId,
            @Param("status") PaymentAttemptStatus status,
            @Param("providerPaymentId") String providerPaymentId
    );

    @Modifying
    @Query("""
            UPDATE PaymentAttemptEntity payment
            SET payment.status = :status,
                payment.lastErrorCode = :errorCode
            WHERE payment.bookingAttemptId = :bookingAttemptId
            """)
    int markFailed(
            @Param("bookingAttemptId") String bookingAttemptId,
            @Param("status") PaymentAttemptStatus status,
            @Param("errorCode") String errorCode
    );

    @Modifying
    @Query("UPDATE PaymentAttemptEntity payment SET payment.status = :status WHERE payment.bookingAttemptId = :bookingAttemptId")
    int markStatus(@Param("bookingAttemptId") String bookingAttemptId, @Param("status") PaymentAttemptStatus status);

    @Modifying
    @Query("""
            UPDATE PaymentAttemptEntity payment
            SET payment.status = :status,
                payment.nextReconcileAt = :nextReconcileAt,
                payment.activeReconcileUntil = COALESCE(payment.activeReconcileUntil, :activeReconcileUntil)
            WHERE payment.bookingAttemptId = :bookingAttemptId
            """)
    int markStatusAndNextReconcile(
            @Param("bookingAttemptId") String bookingAttemptId,
            @Param("status") PaymentAttemptStatus status,
            @Param("nextReconcileAt") LocalDateTime nextReconcileAt,
            @Param("activeReconcileUntil") LocalDateTime activeReconcileUntil
    );

    @Modifying
    @Query("""
            UPDATE PaymentAttemptEntity payment
            SET payment.status = :status,
                payment.manualReviewReason = :reason
            WHERE payment.status IN (com.peakbooking.booking.domain.PaymentAttemptStatus.PAYMENT_UNKNOWN,
                                     com.peakbooking.booking.domain.PaymentAttemptStatus.RECONCILING_AFTER_RELEASE,
                                     com.peakbooking.booking.domain.PaymentAttemptStatus.LATE_SUCCESS_CANCEL_PENDING)
              AND payment.activeReconcileUntil IS NOT NULL
              AND payment.activeReconcileUntil <= :now
            """)
    int markManualReviewAfterWindow(
            @Param("status") PaymentAttemptStatus status,
            @Param("reason") String reason,
            @Param("now") LocalDateTime now
    );

    @Modifying
    @Query("""
            UPDATE PaymentAttemptEntity payment
            SET payment.nextReconcileAt = :nextReconcileAt,
                payment.lastReconcileAt = :now,
                payment.reconcileAttemptCount = payment.reconcileAttemptCount + 1,
                payment.leaseUntil = NULL,
                payment.leaseToken = NULL,
                payment.leaseOwner = NULL
            WHERE payment.bookingAttemptId = :bookingAttemptId
            """)
    int scheduleNextReconcile(
            @Param("bookingAttemptId") String bookingAttemptId,
            @Param("nextReconcileAt") LocalDateTime nextReconcileAt,
            @Param("now") LocalDateTime now
    );

    @Query("""
            SELECT COUNT(payment)
            FROM PaymentAttemptEntity payment
            WHERE payment.bookingAttemptId = :bookingAttemptId
              AND payment.leaseToken = :leaseToken
              AND payment.leaseUntil IS NOT NULL
              AND payment.leaseUntil >= :now
            """)
    long countByBookingAttemptIdAndLeaseToken(
            @Param("bookingAttemptId") String bookingAttemptId,
            @Param("leaseToken") String leaseToken,
            @Param("now") LocalDateTime now
    );
}
