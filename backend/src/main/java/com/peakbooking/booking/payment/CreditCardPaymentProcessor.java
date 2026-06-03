package com.peakbooking.booking.payment;

import com.peakbooking.booking.domain.PaymentMethodType;
import com.peakbooking.booking.domain.PaymentPlanLine;
import org.springframework.stereotype.Component;

@Component
public class CreditCardPaymentProcessor implements PaymentMethodProcessor {

    @Override
    public PaymentMethodType methodType() {
        return PaymentMethodType.CREDIT_CARD;
    }

    @Override
    public PaymentExecutionComponent prepare(PaymentPlanLine line, String bookingAttemptId) {
        return PaymentExecutionComponent.externalProvider(methodType(), line.amount(), bookingAttemptId);
    }
}
