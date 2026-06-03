package com.peakbooking.booking.payment;

import com.peakbooking.booking.domain.PaymentPlanLine;
import com.peakbooking.booking.domain.PaymentMethodType;

public interface PaymentMethodProcessor {

    PaymentMethodType methodType();

    PaymentExecutionComponent prepare(PaymentPlanLine line, String bookingAttemptId);
}
