package com.peakbooking.booking.infrastructure.jpa.entity;

import com.peakbooking.booking.domain.ReservationStatus;
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
@Table(name = "reservation")
public class ReservationEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private long admissionId;

    @Column(nullable = false)
    private String bookingAttemptId;

    @Column(nullable = false)
    private long saleEventId;

    @Column(nullable = false)
    private long productId;

    @Column(nullable = false)
    private long userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ReservationStatus status;

    private LocalDateTime holdExpiresAt;
    private LocalDateTime unknownInventoryDeadlineAt;
    private String releasedReason;
    private LocalDateTime confirmedAt;

    protected ReservationEntity() {
    }

    private ReservationEntity(
            long admissionId,
            String bookingAttemptId,
            long saleEventId,
            long productId,
            long userId,
            ReservationStatus status,
            LocalDateTime holdExpiresAt
    ) {
        this.admissionId = admissionId;
        this.bookingAttemptId = bookingAttemptId;
        this.saleEventId = saleEventId;
        this.productId = productId;
        this.userId = userId;
        this.status = status;
        this.holdExpiresAt = holdExpiresAt;
    }

    public static ReservationEntity held(
            long admissionId,
            String bookingAttemptId,
            long saleEventId,
            long productId,
            long userId,
            LocalDateTime holdExpiresAt
    ) {
        return new ReservationEntity(
                admissionId,
                bookingAttemptId,
                saleEventId,
                productId,
                userId,
                ReservationStatus.HELD,
                holdExpiresAt
        );
    }

    public Long getId() {
        return id;
    }

    public long getAdmissionId() {
        return admissionId;
    }

    public String getBookingAttemptId() {
        return bookingAttemptId;
    }

    public long getSaleEventId() {
        return saleEventId;
    }

    public long getProductId() {
        return productId;
    }

    public long getUserId() {
        return userId;
    }

    public ReservationStatus getStatus() {
        return status;
    }

    public LocalDateTime getHoldExpiresAt() {
        return holdExpiresAt;
    }

    public LocalDateTime getUnknownInventoryDeadlineAt() {
        return unknownInventoryDeadlineAt;
    }
}
