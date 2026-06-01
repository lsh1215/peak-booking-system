package com.peakbooking.booking.infrastructure.persistence;

public record InventorySnapshot(int totalCount, int reservedCount, int paymentUnknownCount, int confirmedCount) {

    public int occupiedCount() {
        return reservedCount + paymentUnknownCount + confirmedCount;
    }
}
