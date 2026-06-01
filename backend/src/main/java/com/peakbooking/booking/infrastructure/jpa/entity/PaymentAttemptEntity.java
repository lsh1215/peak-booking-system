package com.peakbooking.booking.infrastructure.jpa.entity;

import com.peakbooking.booking.domain.PaymentAttemptStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;

@Entity
@Table(name = "payment_attempt")
public class PaymentAttemptEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String bookingAttemptId;

    private Long reservationId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PaymentAttemptStatus status;

    @Column(nullable = false)
    private String methodType;

    @Column(nullable = false)
    private long amount;

    @Column(nullable = false)
    private String providerOrderId;

    private String providerPaymentId;
    private LocalDateTime confirmStartedAt;
    private LocalDateTime firstUnknownAt;
    private LocalDateTime activeReconcileUntil;
    private LocalDateTime nextReconcileAt;

    @Column(nullable = false)
    private int reconcileAttemptCount;

    private LocalDateTime lastReconcileAt;
    private String leaseOwner;
    private String leaseToken;
    private LocalDateTime leaseUntil;
    private String lastErrorCode;
    private String manualReviewReason;

    protected PaymentAttemptEntity() {
    }

    private PaymentAttemptEntity(
            String bookingAttemptId,
            long reservationId,
            String methodType,
            long amount,
            String providerOrderId
    ) {
        this.bookingAttemptId = bookingAttemptId;
        this.reservationId = reservationId;
        this.status = PaymentAttemptStatus.REQUESTED;
        this.methodType = methodType;
        this.amount = amount;
        this.providerOrderId = providerOrderId;
    }

    public static PaymentAttemptEntity requested(
            String bookingAttemptId,
            long reservationId,
            String methodType,
            long amount,
            String providerOrderId
    ) {
        return new PaymentAttemptEntity(bookingAttemptId, reservationId, methodType, amount, providerOrderId);
    }

    public Long getId() {
        return id;
    }

    public String getBookingAttemptId() {
        return bookingAttemptId;
    }

    public Long getReservationId() {
        return reservationId;
    }

    public PaymentAttemptStatus getStatus() {
        return status;
    }

    public String getProviderOrderId() {
        return providerOrderId;
    }

    public String getProviderPaymentId() {
        return providerPaymentId;
    }

    public LocalDateTime getFirstUnknownAt() {
        return firstUnknownAt;
    }

    public LocalDateTime getActiveReconcileUntil() {
        return activeReconcileUntil;
    }

    public LocalDateTime getNextReconcileAt() {
        return nextReconcileAt;
    }

    public int getReconcileAttemptCount() {
        return reconcileAttemptCount;
    }

    public LocalDateTime getConfirmStartedAt() {
        return confirmStartedAt;
    }

    public String getLeaseToken() {
        return leaseToken;
    }
}
