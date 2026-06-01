package com.peakbooking.booking.payment;

import com.peakbooking.booking.application.BookingCommand;
import com.peakbooking.booking.application.MockPgScenario;
import com.peakbooking.booking.config.BookingProperties;
import com.peakbooking.booking.infrastructure.jpa.entity.MockPgPaymentEntity;
import com.peakbooking.booking.infrastructure.jpa.repository.MockPgPaymentJpaRepository;
import java.time.Duration;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

@Component
public class MockPaymentProvider implements PaymentProvider {

    private final MockPgPaymentJpaRepository repository;
    private final BookingProperties properties;
    private final TransactionTemplate transactionTemplate;

    public MockPaymentProvider(
            MockPgPaymentJpaRepository repository,
            BookingProperties properties,
            TransactionTemplate transactionTemplate
    ) {
        this.repository = repository;
        this.properties = properties;
        this.transactionTemplate = transactionTemplate;
    }

    @Override
    public PaymentConfirmResult confirm(String providerOrderId, String bookingAttemptId, BookingCommand command) {
        String providerPaymentId = "mock_pg_" + providerOrderId;
        sleep(confirmDelay(command.mockPgScenario()));
        transactionTemplate.execute(status -> {
            ensurePayment(providerOrderId, providerPaymentId, command.mockPgScenario());
            repository.incrementConfirmCount(providerOrderId);
            return null;
        });
        return switch (command.mockPgScenario()) {
            case SUCCESS -> PaymentConfirmResult.success(providerPaymentId);
            case FAILURE -> PaymentConfirmResult.failure("MOCK_PAYMENT_FAILED");
            case TIMEOUT, LATE_SUCCESS -> PaymentConfirmResult.unknown(providerPaymentId, "MOCK_TIMEOUT");
        };
    }

    @Override
    public PaymentStatusResult query(String providerPaymentId) {
        MockPgPaymentSnapshot payment = transactionTemplate.execute(status -> repository.findByProviderPaymentId(providerPaymentId)
                .map(MockPgPaymentSnapshot::from)
                .orElse(null));
        if (payment == null) {
            return PaymentStatusResult.unknown("MOCK_PAYMENT_NOT_FOUND");
        }
        sleep(queryDelay(payment));
        return toStatusResult(payment);
    }

    @Override
    public PaymentStatusResult queryByOrderId(String providerOrderId) {
        MockPgPaymentSnapshot payment = transactionTemplate.execute(status -> repository.findByProviderOrderId(providerOrderId)
                .map(MockPgPaymentSnapshot::from)
                .orElse(null));
        if (payment == null) {
            return PaymentStatusResult.unknown("MOCK_PAYMENT_NOT_FOUND");
        }
        sleep(queryDelay(payment));
        return toStatusResult(payment);
    }

    @Override
    public PaymentStatusResult cancel(String providerPaymentId, String reason) {
        int updated = transactionTemplate.execute(status -> repository.cancelByPaymentId(providerPaymentId, reason));
        if (updated == 0) {
            return query(providerPaymentId);
        }
        return PaymentStatusResult.cancelled();
    }

    @Override
    public PaymentStatusResult cancelByOrderId(String providerOrderId, String reason) {
        int updated = transactionTemplate.execute(status -> repository.cancelByOrderId(providerOrderId, reason));
        if (updated == 0) {
            return queryByOrderId(providerOrderId);
        }
        return PaymentStatusResult.cancelled();
    }

    public int confirmCount(String bookingAttemptId) {
        return transactionTemplate.execute(status -> repository.confirmCount(bookingAttemptId));
    }

    private void ensurePayment(String providerOrderId, String providerPaymentId, MockPgScenario scenario) {
        try {
            repository.saveAndFlush(MockPgPaymentEntity.create(
                    providerOrderId,
                    providerPaymentId,
                    scenario,
                    initialStatus(scenario),
                    scenario == MockPgScenario.FAILURE ? "MOCK_PAYMENT_FAILED" : null
            ));
        } catch (DataIntegrityViolationException ignored) {
            // provider_order_id is the PG idempotency key; a retry observes the existing mock row.
        }
    }

    private PaymentStatus initialStatus(MockPgScenario scenario) {
        return switch (scenario) {
            case SUCCESS, LATE_SUCCESS -> PaymentStatus.APPROVED;
            case FAILURE -> PaymentStatus.FAILED;
            case TIMEOUT -> PaymentStatus.UNKNOWN;
        };
    }

    private Duration confirmDelay(MockPgScenario scenario) {
        return switch (scenario) {
            case SUCCESS, FAILURE -> properties.payment().mockNormalDelay();
            case TIMEOUT, LATE_SUCCESS -> properties.payment().mockTimeoutDelay();
        };
    }

    private Duration queryDelay(MockPgPaymentSnapshot payment) {
        return payment.scenario() == MockPgScenario.TIMEOUT
                ? properties.payment().mockTimeoutDelay()
                : Duration.ZERO;
    }

    private void sleep(Duration delay) {
        if (delay == null || delay.isZero() || delay.isNegative()) {
            return;
        }
        try {
            Thread.sleep(delay.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private PaymentStatusResult toStatusResult(MockPgPaymentSnapshot payment) {
        return switch (payment.status()) {
            case APPROVED -> PaymentStatusResult.approved(payment.providerPaymentId());
            case FAILED -> PaymentStatusResult.failed(
                    payment.lastErrorCode() == null ? "MOCK_PAYMENT_FAILED" : payment.lastErrorCode()
            );
            case CANCELLED -> PaymentStatusResult.cancelled();
            case UNKNOWN -> PaymentStatusResult.unknown(
                    payment.lastErrorCode() == null ? "MOCK_STILL_UNKNOWN" : payment.lastErrorCode()
            );
        };
    }

    private record MockPgPaymentSnapshot(
            String providerPaymentId,
            MockPgScenario scenario,
            PaymentStatus status,
            String lastErrorCode
    ) {

        private static MockPgPaymentSnapshot from(MockPgPaymentEntity payment) {
            return new MockPgPaymentSnapshot(
                    payment.getProviderPaymentId(),
                    payment.getScenario(),
                    payment.getStatus(),
                    payment.getLastErrorCode()
            );
        }
    }
}
