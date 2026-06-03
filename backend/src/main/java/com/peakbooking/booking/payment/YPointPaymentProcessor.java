package com.peakbooking.booking.payment;

import com.peakbooking.booking.domain.PaymentMethodType;
import com.peakbooking.booking.domain.PaymentPlanLine;
import org.springframework.stereotype.Component;

@Component
public class YPointPaymentProcessor implements PaymentMethodProcessor {

    @Override
    public PaymentMethodType methodType() {
        return PaymentMethodType.Y_POINT;
    }

    @Override
    public PaymentExecutionComponent prepare(PaymentPlanLine line, String bookingAttemptId) {
        return PaymentExecutionComponent.internalLedger(methodType(), line.amount());
    }
}
