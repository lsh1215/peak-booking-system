package com.peakbooking.booking.payment;

import com.peakbooking.booking.application.dto.BookingCommand;
import com.peakbooking.booking.config.BookingProperties;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;

@Component
public class PaymentCallGuard {

    private final PaymentProvider paymentProvider;
    private final Duration timeout;
    private final Semaphore confirmSemaphore;
    private final Semaphore recoverySemaphore;
    private final ExecutorService executor;

    public PaymentCallGuard(BookingProperties properties, PaymentProvider paymentProvider) {
        this.paymentProvider = paymentProvider;
        this.timeout = properties.payment().callTimeout();
        this.confirmSemaphore = new Semaphore(properties.bulkhead().pgConfirmConcurrency());
        this.recoverySemaphore = new Semaphore(properties.bulkhead().recoveryPgConcurrency());
        int threads = Math.max(
                1,
                properties.bulkhead().pgConfirmConcurrency() + properties.bulkhead().recoveryPgConcurrency()
        );
        this.executor = Executors.newFixedThreadPool(threads);
    }

    public PaymentConfirmResult confirm(String providerOrderId, String bookingAttemptId, BookingCommand command) {
        if (!confirmSemaphore.tryAcquire()) {
            return PaymentConfirmResult.unknown(null, "PG_CONFIRM_BULKHEAD_FULL");
        }
        try {
            return callWithTimeout(
                    () -> paymentProvider.confirm(providerOrderId, bookingAttemptId, command),
                    PaymentConfirmResult.unknown(null, "PG_CONFIRM_TIMEOUT_OR_ERROR")
            );
        } finally {
            confirmSemaphore.release();
        }
    }

    public PaymentStatusResult queryByOrderId(String providerOrderId) {
        return guardedRecoveryCall(
                () -> paymentProvider.queryByOrderId(providerOrderId),
                "PG_QUERY_TIMEOUT_OR_ERROR"
        );
    }

    public PaymentStatusResult query(String providerPaymentId) {
        return guardedRecoveryCall(
                () -> paymentProvider.query(providerPaymentId),
                "PG_QUERY_TIMEOUT_OR_ERROR"
        );
    }

    public PaymentStatusResult cancelByOrderId(String providerOrderId, String reason) {
        return guardedRecoveryCall(
                () -> paymentProvider.cancelByOrderId(providerOrderId, reason),
                "PG_CANCEL_TIMEOUT_OR_ERROR"
        );
    }

    public PaymentStatusResult cancel(String providerPaymentId, String reason) {
        return guardedRecoveryCall(
                () -> paymentProvider.cancel(providerPaymentId, reason),
                "PG_CANCEL_TIMEOUT_OR_ERROR"
        );
    }

    private PaymentStatusResult guardedRecoveryCall(Supplier<PaymentStatusResult> supplier, String errorCode) {
        if (!recoverySemaphore.tryAcquire()) {
            return PaymentStatusResult.unknown("PG_RECOVERY_BULKHEAD_FULL");
        }
        try {
            return callWithTimeout(supplier, PaymentStatusResult.unknown(errorCode));
        } finally {
            recoverySemaphore.release();
        }
    }

    private <T> T callWithTimeout(Supplier<T> supplier, T fallback) {
        CompletableFuture<T> future = CompletableFuture.supplyAsync(supplier, executor);
        try {
            return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (Exception ignored) {
            future.cancel(true);
            return fallback;
        }
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }
}
