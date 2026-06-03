package com.peakbooking.booking.infrastructure.jpa.entity;

import com.peakbooking.booking.payment.MockPgScenario;
import com.peakbooking.booking.payment.PaymentStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "mock_pg_payment")
public class MockPgPaymentEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String providerOrderId;

    private String providerPaymentId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private MockPgScenario scenario;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PaymentStatus status;

    @Column(nullable = false)
    private int confirmCount;

    @Column(nullable = false)
    private int cancelCount;

    private String lastErrorCode;

    protected MockPgPaymentEntity() {
    }

    private MockPgPaymentEntity(
            String providerOrderId,
            String providerPaymentId,
            MockPgScenario scenario,
            PaymentStatus status,
            String lastErrorCode
    ) {
        this.providerOrderId = providerOrderId;
        this.providerPaymentId = providerPaymentId;
        this.scenario = scenario;
        this.status = status;
        this.lastErrorCode = lastErrorCode;
    }

    public static MockPgPaymentEntity create(
            String providerOrderId,
            String providerPaymentId,
            MockPgScenario scenario,
            PaymentStatus status,
            String lastErrorCode
    ) {
        return new MockPgPaymentEntity(providerOrderId, providerPaymentId, scenario, status, lastErrorCode);
    }

    public PaymentStatus getStatus() {
        return status;
    }

    public MockPgScenario getScenario() {
        return scenario;
    }

    public String getProviderPaymentId() {
        return providerPaymentId;
    }

    public String getLastErrorCode() {
        return lastErrorCode;
    }
}
