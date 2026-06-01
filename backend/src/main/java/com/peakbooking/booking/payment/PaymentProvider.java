package com.peakbooking.booking.payment;

import com.peakbooking.booking.application.BookingCommand;

public interface PaymentProvider {

    PaymentConfirmResult confirm(String providerOrderId, String bookingAttemptId, BookingCommand command);

    PaymentStatusResult query(String providerPaymentId);

    PaymentStatusResult queryByOrderId(String providerOrderId);

    PaymentStatusResult cancel(String providerPaymentId, String reason);

    PaymentStatusResult cancelByOrderId(String providerOrderId, String reason);
}
