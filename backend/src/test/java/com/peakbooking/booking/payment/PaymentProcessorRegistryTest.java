package com.peakbooking.booking.payment;

import static org.assertj.core.api.Assertions.assertThat;

import com.peakbooking.booking.domain.PaymentMethodType;
import com.peakbooking.booking.domain.PaymentPlan;
import com.peakbooking.booking.domain.PaymentPlanLine;
import java.util.List;
import org.junit.jupiter.api.Test;

class PaymentProcessorRegistryTest {

    private final PaymentProcessorRegistry registry = new PaymentProcessorRegistry(List.of(
            new CreditCardPaymentProcessor(),
            new YPayPaymentProcessor(),
            new YPointPaymentProcessor()
    ));

    @Test
    void should_plan_y_point_only_as_internal_payment_without_external_provider() {
        PaymentExecutionPlan plan = registry.plan(PaymentPlan.from(List.of(
                new PaymentPlanLine(PaymentMethodType.Y_POINT, 10000)
        )), "attempt-1");

        assertThat(plan.requiresExternalConfirmation()).isFalse();
        assertThat(plan.externalComponent()).isEmpty();
        assertThat(plan.pointAmount()).isEqualTo(10000);
        assertThat(plan.components())
                .singleElement()
                .satisfies(component -> {
                    assertThat(component.methodType()).isEqualTo(PaymentMethodType.Y_POINT);
                    assertThat(component.kind()).isEqualTo(PaymentExecutionKind.INTERNAL_LEDGER);
                    assertThat(component.providerOrderId()).isNull();
                });
    }

    @Test
    void should_plan_y_pay_with_y_point_as_one_external_and_one_internal_component() {
        PaymentExecutionPlan plan = registry.plan(PaymentPlan.from(List.of(
                new PaymentPlanLine(PaymentMethodType.Y_PAY, 9000),
                new PaymentPlanLine(PaymentMethodType.Y_POINT, 1000)
        )), "attempt-2");

        assertThat(plan.requiresExternalConfirmation()).isTrue();
        assertThat(plan.externalComponent())
                .hasValueSatisfying(component -> {
                    assertThat(component.methodType()).isEqualTo(PaymentMethodType.Y_PAY);
                    assertThat(component.amount()).isEqualTo(9000);
                    assertThat(component.kind()).isEqualTo(PaymentExecutionKind.EXTERNAL_PROVIDER);
                    assertThat(component.providerOrderId()).isEqualTo("attempt-2");
                });
        assertThat(plan.pointAmount()).isEqualTo(1000);
    }
}
