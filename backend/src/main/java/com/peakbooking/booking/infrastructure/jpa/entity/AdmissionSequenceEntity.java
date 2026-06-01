package com.peakbooking.booking.infrastructure.jpa.entity;

import com.peakbooking.booking.domain.GateMode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "admission_sequence")
public class AdmissionSequenceEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private long saleEventId;

    @Column(nullable = false)
    private long productId;

    @Column(nullable = false)
    private long nextSeq;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private GateMode gateMode;

    protected AdmissionSequenceEntity() {
    }

    public Long getId() {
        return id;
    }

    public long getNextSeq() {
        return nextSeq;
    }

    public GateMode getGateMode() {
        return gateMode;
    }
}
