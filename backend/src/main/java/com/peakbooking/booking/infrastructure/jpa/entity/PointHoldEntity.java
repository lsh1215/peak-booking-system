package com.peakbooking.booking.infrastructure.jpa.entity;

import com.peakbooking.booking.domain.PointHoldStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "point_hold")
public class PointHoldEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String bookingAttemptId;

    @Column(nullable = false)
    private long pointAccountId;

    @Column(nullable = false)
    private long amount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PointHoldStatus status;

    protected PointHoldEntity() {
    }

    private PointHoldEntity(String bookingAttemptId, long pointAccountId, long amount) {
        this.bookingAttemptId = bookingAttemptId;
        this.pointAccountId = pointAccountId;
        this.amount = amount;
        this.status = PointHoldStatus.HELD;
    }

    public static PointHoldEntity held(String bookingAttemptId, long pointAccountId, long amount) {
        return new PointHoldEntity(bookingAttemptId, pointAccountId, amount);
    }

    public Long getId() {
        return id;
    }

    public long getPointAccountId() {
        return pointAccountId;
    }

    public long getAmount() {
        return amount;
    }
}
