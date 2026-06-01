package com.peakbooking.booking.infrastructure.jpa.entity;

import com.peakbooking.booking.domain.AdmissionStatus;
import com.peakbooking.booking.domain.GateMode;
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
@Table(name = "booking_admission")
public class BookingAdmissionEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private long saleEventId;

    @Column(nullable = false)
    private long productId;

    @Column(nullable = false)
    private long userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private GateMode gateMode;

    private Long redisSeq;
    private Long dbAdmissionSeq;
    private Integer candidateRank;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AdmissionStatus status;

    private String bookingAttemptId;
    private LocalDateTime waitingExpiresAt;

    @Column(nullable = false)
    private LocalDateTime admittedAt;

    private LocalDateTime completedAt;

    protected BookingAdmissionEntity() {
    }

    private BookingAdmissionEntity(
            long saleEventId,
            long productId,
            long userId,
            GateMode gateMode,
            Long redisSeq,
            Long dbAdmissionSeq,
            Integer candidateRank,
            AdmissionStatus status,
            String bookingAttemptId,
            LocalDateTime admittedAt
    ) {
        this.saleEventId = saleEventId;
        this.productId = productId;
        this.userId = userId;
        this.gateMode = gateMode;
        this.redisSeq = redisSeq;
        this.dbAdmissionSeq = dbAdmissionSeq;
        this.candidateRank = candidateRank;
        this.status = status;
        this.bookingAttemptId = bookingAttemptId;
        this.admittedAt = admittedAt;
    }

    public static BookingAdmissionEntity admitted(
            long saleEventId,
            long productId,
            long userId,
            GateMode gateMode,
            Long redisSeq,
            Long dbAdmissionSeq,
            Integer candidateRank,
            String bookingAttemptId,
            LocalDateTime admittedAt
    ) {
        return new BookingAdmissionEntity(
                saleEventId,
                productId,
                userId,
                gateMode,
                redisSeq,
                dbAdmissionSeq,
                candidateRank,
                AdmissionStatus.ADMITTED,
                bookingAttemptId,
                admittedAt
        );
    }

    public Long getId() {
        return id;
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

    public Long getDbAdmissionSeq() {
        return dbAdmissionSeq;
    }

    public Long getRedisSeq() {
        return redisSeq;
    }

    public Integer getCandidateRank() {
        return candidateRank;
    }

    public GateMode getGateMode() {
        return gateMode;
    }

    public AdmissionStatus getStatus() {
        return status;
    }

    public String getBookingAttemptId() {
        return bookingAttemptId;
    }

    public LocalDateTime getWaitingExpiresAt() {
        return waitingExpiresAt;
    }
}
