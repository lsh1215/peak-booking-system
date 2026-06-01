package com.peakbooking.booking.payment;

import com.peakbooking.booking.application.BookingCommand;
import com.peakbooking.booking.application.MockPgScenario;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.stereotype.Component;

@Component
public class MockPaymentProvider implements PaymentProvider {

    private final Map<String, MockPgScenario> scenarios = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> confirmCounts = new ConcurrentHashMap<>();

    @Override
    public PaymentConfirmResult confirm(String bookingAttemptId, BookingCommand command) {
        confirmCounts.computeIfAbsent(bookingAttemptId, ignored -> new AtomicInteger()).incrementAndGet();
        String providerPaymentId = "mock_pg_" + bookingAttemptId;
        scenarios.put(providerPaymentId, command.mockPgScenario());
        return switch (command.mockPgScenario()) {
            case SUCCESS -> PaymentConfirmResult.success(providerPaymentId);
            case FAILURE -> PaymentConfirmResult.failure("MOCK_PAYMENT_FAILED");
            case TIMEOUT, LATE_SUCCESS -> PaymentConfirmResult.unknown(providerPaymentId, "MOCK_TIMEOUT");
        };
    }

    @Override
    public PaymentStatusResult query(String providerPaymentId) {
        MockPgScenario scenario = scenarios.getOrDefault(providerPaymentId, MockPgScenario.SUCCESS);
        return switch (scenario) {
            case SUCCESS, LATE_SUCCESS -> PaymentStatusResult.approved();
            case FAILURE -> PaymentStatusResult.failed("MOCK_PAYMENT_FAILED");
            case TIMEOUT -> PaymentStatusResult.unknown("MOCK_STILL_UNKNOWN");
        };
    }

    @Override
    public PaymentStatusResult cancel(String providerPaymentId, String reason) {
        scenarios.put(providerPaymentId, MockPgScenario.FAILURE);
        return PaymentStatusResult.cancelled();
    }

    public int confirmCount(String bookingAttemptId) {
        return confirmCounts.getOrDefault(bookingAttemptId, new AtomicInteger()).get();
    }
}
