package com.peakbooking.booking.domain;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.peakbooking.common.exception.BusinessException;
import java.util.List;
import org.junit.jupiter.api.Test;

class CombinationPolicyTest {

    private final CombinationPolicy policy = new CombinationPolicy();

    @Test
    void should_accept_credit_card_with_y_point_when_total_matches() {
        PaymentPlan plan = PaymentPlan.from(List.of(
                new PaymentPlanLine(PaymentMethodType.CREDIT_CARD, 9000),
                new PaymentPlanLine(PaymentMethodType.Y_POINT, 1000)
        ));

        assertThatCode(() -> policy.validate(plan, 10000))
                .doesNotThrowAnyException();
    }

    @Test
    void should_reject_credit_card_and_y_pay_when_mixed() {
        PaymentPlan plan = PaymentPlan.from(List.of(
                new PaymentPlanLine(PaymentMethodType.CREDIT_CARD, 5000),
                new PaymentPlanLine(PaymentMethodType.Y_PAY, 5000)
        ));

        assertThatThrownBy(() -> policy.validate(plan, 10000))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void should_reject_plan_when_amount_does_not_match_total() {
        PaymentPlan plan = PaymentPlan.from(List.of(
                new PaymentPlanLine(PaymentMethodType.Y_PAY, 9000)
        ));

        assertThatThrownBy(() -> policy.validate(plan, 10000))
                .isInstanceOf(BusinessException.class);
    }
}
