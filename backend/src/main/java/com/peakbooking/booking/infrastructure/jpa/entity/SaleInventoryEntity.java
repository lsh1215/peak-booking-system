package com.peakbooking.booking.infrastructure.jpa.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "sale_inventory")
public class SaleInventoryEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private long saleEventId;

    @Column(nullable = false)
    private long productId;

    @Column(nullable = false)
    private int totalCount;

    @Column(nullable = false)
    private int reservedCount;

    @Column(nullable = false)
    private int paymentUnknownCount;

    @Column(nullable = false)
    private int confirmedCount;

    protected SaleInventoryEntity() {
    }

    public int getTotalCount() {
        return totalCount;
    }

    public int getReservedCount() {
        return reservedCount;
    }

    public int getPaymentUnknownCount() {
        return paymentUnknownCount;
    }

    public int getConfirmedCount() {
        return confirmedCount;
    }
}
