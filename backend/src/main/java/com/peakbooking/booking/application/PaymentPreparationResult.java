package com.peakbooking.booking.application;

public record PaymentPreparationResult(Status status, Long reservationId) {

    public enum Status {
        CREATED,
        EXISTING_IN_PROGRESS,
        TERMINAL_ADMISSION,
        WAITING
    }

    public static PaymentPreparationResult created(long reservationId) {
        return new PaymentPreparationResult(Status.CREATED, reservationId);
    }

    public static PaymentPreparationResult existing(long reservationId) {
        return new PaymentPreparationResult(Status.EXISTING_IN_PROGRESS, reservationId);
    }

    public static PaymentPreparationResult terminalAdmission() {
        return new PaymentPreparationResult(Status.TERMINAL_ADMISSION, null);
    }

    public static PaymentPreparationResult waiting() {
        return new PaymentPreparationResult(Status.WAITING, null);
    }
}
