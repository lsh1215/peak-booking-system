package com.peakbooking.booking.payment;

import com.peakbooking.booking.application.BookingCommand;

public interface PaymentProvider {

    PaymentConfirmResult confirm(String bookingAttemptId, BookingCommand command);

    PaymentStatusResult query(String providerPaymentId);

    PaymentStatusResult cancel(String providerPaymentId, String reason);
}
