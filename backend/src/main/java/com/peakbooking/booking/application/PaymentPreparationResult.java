package com.peakbooking.booking.application;

import com.peakbooking.booking.payment.PaymentExecutionComponent;

public record PaymentPreparationResult(
        Status status,
        Long reservationId,
        PaymentExecutionComponent externalComponent,
        BookingResult terminalResult
) {

    public enum Status {
        CREATED,
        EXISTING_IN_PROGRESS,
        TERMINAL_ADMISSION,
        WAITING,
        FAILED
    }

    public static PaymentPreparationResult created(long reservationId, PaymentExecutionComponent externalComponent) {
        return new PaymentPreparationResult(Status.CREATED, reservationId, externalComponent, null);
    }

    public static PaymentPreparationResult existing(long reservationId) {
        return new PaymentPreparationResult(Status.EXISTING_IN_PROGRESS, reservationId, null, null);
    }

    public static PaymentPreparationResult terminalAdmission() {
        return new PaymentPreparationResult(Status.TERMINAL_ADMISSION, null, null, null);
    }

    public static PaymentPreparationResult waiting() {
        return new PaymentPreparationResult(Status.WAITING, null, null, null);
    }

    public static PaymentPreparationResult failed(BookingResult result) {
        return new PaymentPreparationResult(Status.FAILED, result.reservationId(), null, result);
    }

    public boolean requiresExternalConfirmation() {
        return externalComponent != null;
    }
}
