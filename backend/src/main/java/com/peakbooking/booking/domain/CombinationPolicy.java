package com.peakbooking.booking.domain;

import com.peakbooking.common.exception.BusinessException;
import org.springframework.stereotype.Component;

@Component
public class CombinationPolicy {

    public void validate(PaymentPlan plan, long expectedTotalAmount) {
        if (plan.has(PaymentMethodType.CREDIT_CARD) && plan.has(PaymentMethodType.Y_PAY)) {
            throw new BusinessException(BookingErrorCode.PAYMENT_COMBINATION_NOT_ALLOWED);
        }
        if (plan.totalAmount() != expectedTotalAmount) {
            throw new BusinessException(BookingErrorCode.PAYMENT_AMOUNT_MISMATCH);
        }
        if (plan.amounts().isEmpty()) {
            throw new BusinessException(BookingErrorCode.PAYMENT_AMOUNT_MISMATCH);
        }
    }
}
