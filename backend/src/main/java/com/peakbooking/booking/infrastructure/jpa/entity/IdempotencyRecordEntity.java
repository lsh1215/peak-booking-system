package com.peakbooking.booking.infrastructure.jpa.entity;

import com.peakbooking.booking.domain.IdempotencyStatus;
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
@Table(name = "idempotency_record")
public class IdempotencyRecordEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String bookingAttemptId;

    @Column(nullable = false, length = 64)
    private String requestHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private IdempotencyStatus status;

    private Integer httpStatus;
    private String businessCode;

    @Column(columnDefinition = "TEXT")
    private String responseSnapshot;

    private Long reservationId;

    @Column(nullable = false)
    private LocalDateTime expiresAt;

    protected IdempotencyRecordEntity() {
    }

    private IdempotencyRecordEntity(String bookingAttemptId, String requestHash, LocalDateTime expiresAt) {
        this.bookingAttemptId = bookingAttemptId;
        this.requestHash = requestHash;
        this.status = IdempotencyStatus.IN_PROGRESS;
        this.expiresAt = expiresAt;
    }

    public static IdempotencyRecordEntity inProgress(
            String bookingAttemptId,
            String requestHash,
            LocalDateTime expiresAt
    ) {
        return new IdempotencyRecordEntity(bookingAttemptId, requestHash, expiresAt);
    }

    public String getBookingAttemptId() {
        return bookingAttemptId;
    }

    public String getRequestHash() {
        return requestHash;
    }

    public IdempotencyStatus getStatus() {
        return status;
    }

    public Integer getHttpStatus() {
        return httpStatus;
    }

    public String getBusinessCode() {
        return businessCode;
    }

    public String getResponseSnapshot() {
        return responseSnapshot;
    }

    public Long getReservationId() {
        return reservationId;
    }
}
