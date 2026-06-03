package com.peakbooking.booking.payment;

import com.peakbooking.booking.domain.BookingErrorCode;
import com.peakbooking.booking.domain.PaymentMethodType;
import com.peakbooking.booking.domain.PaymentPlan;
import com.peakbooking.booking.domain.PaymentPlanLine;
import com.peakbooking.common.exception.BusinessException;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class PaymentProcessorRegistry {

    private final Map<PaymentMethodType, PaymentMethodProcessor> processors;

    public PaymentProcessorRegistry(List<PaymentMethodProcessor> processors) {
        EnumMap<PaymentMethodType, PaymentMethodProcessor> mapped = new EnumMap<>(PaymentMethodType.class);
        for (PaymentMethodProcessor processor : processors) {
            mapped.put(processor.methodType(), processor);
        }
        this.processors = Map.copyOf(mapped);
    }

    public PaymentExecutionPlan plan(PaymentPlan paymentPlan, String bookingAttemptId) {
        List<PaymentExecutionComponent> components = paymentPlan.orderedLines().stream()
                .map(line -> processor(line).prepare(line, bookingAttemptId))
                .toList();
        long externalCount = components.stream()
                .filter(PaymentExecutionComponent::isExternalProvider)
                .count();
        if (externalCount > 1) {
            throw new BusinessException(BookingErrorCode.PAYMENT_COMBINATION_NOT_ALLOWED);
        }
        return new PaymentExecutionPlan(components);
    }

    private PaymentMethodProcessor processor(PaymentPlanLine line) {
        PaymentMethodProcessor processor = processors.get(line.type());
        if (processor == null) {
            throw new BusinessException(BookingErrorCode.PAYMENT_COMBINATION_NOT_ALLOWED);
        }
        return processor;
    }
}
