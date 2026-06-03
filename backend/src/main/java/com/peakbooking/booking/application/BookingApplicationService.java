package com.peakbooking.booking.application;

import com.peakbooking.booking.config.BookingProperties;
import com.peakbooking.booking.domain.AdmissionDecision;
import com.peakbooking.booking.domain.AdmissionResult;
import com.peakbooking.booking.domain.BookingErrorCode;
import com.peakbooking.booking.domain.CombinationPolicy;
import com.peakbooking.booking.domain.GateMode;
import com.peakbooking.booking.infrastructure.jpa.BookingJpaRepository;
import com.peakbooking.booking.payment.PaymentConfirmResult;
import com.peakbooking.booking.payment.PaymentConfirmStatus;
import com.peakbooking.booking.payment.PaymentCallGuard;
import com.peakbooking.common.exception.BusinessException;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class BookingApplicationService {

    private final BookingProperties properties;
    private final BookingJpaRepository repository;
    private final AttemptTokenService attemptTokenService;
    private final CanonicalRequestHashCalculator requestHashCalculator;
    private final CombinationPolicy combinationPolicy;
    private final BookingAdmissionService admissionService;
    private final BookingTransactionService transactionService;
    private final BookingDbWriteBulkhead dbWriteBulkhead;
    private final PaymentCallGuard paymentCallGuard;
    private final Clock clock;
    private final Duration productCacheTtl;
    private final ConcurrentMap<Long, CachedProductSummary> productCache = new ConcurrentHashMap<>();

    public BookingApplicationService(
            BookingProperties properties,
            BookingJpaRepository repository,
            AttemptTokenService attemptTokenService,
            CanonicalRequestHashCalculator requestHashCalculator,
            CombinationPolicy combinationPolicy,
            BookingAdmissionService admissionService,
            BookingTransactionService transactionService,
            BookingDbWriteBulkhead dbWriteBulkhead,
            PaymentCallGuard paymentCallGuard,
            Clock clock,
            @Value("${peak-booking.product-cache-ttl:1s}") Duration productCacheTtl
    ) {
        this.properties = properties;
        this.repository = repository;
        this.attemptTokenService = attemptTokenService;
        this.requestHashCalculator = requestHashCalculator;
        this.combinationPolicy = combinationPolicy;
        this.admissionService = admissionService;
        this.transactionService = transactionService;
        this.dbWriteBulkhead = dbWriteBulkhead;
        this.paymentCallGuard = paymentCallGuard;
        this.clock = clock;
        this.productCacheTtl = productCacheTtl;
    }

    public BookingResult book(BookingCommand command) {
        return doBook(command);
    }

    private BookingResult doBook(BookingCommand command) {
        AttemptToken token = attemptTokenService.verify(
                command.bookingAttemptToken(),
                command.userId(),
                command.saleEventId(),
                command.productId()
        );
        String requestHash = requestHashCalculator.hash(command, token.attemptId());

        ProductSummary product = productSummary(command.productId());
        if (product.saleOpenAt().isAfter(LocalDateTime.now(clock))) {
            throw new BusinessException(BookingErrorCode.SALE_NOT_OPEN);
        }
        combinationPolicy.validate(command.paymentPlan(), product.priceAmount());

        AdmissionDecision admission = admissionService.admit(
                command.saleEventId(),
                command.productId(),
                command.userId(),
                token.attemptId()
        );
        if (admission.result() == AdmissionResult.REJECTED) {
            return rejectedAdmission(token.attemptId(), admission.gateMode());
        }
        Optional<BookingResult> canonicalAfterAdmission = dbWriteBulkhead.execute(() ->
                transactionService.canonicalStateForDifferentAttempt(
                admission.admissionId(),
                token.attemptId()
        ));
        if (canonicalAfterAdmission.isPresent()) {
            return canonicalAfterAdmission.get();
        }

        Optional<BookingResult> replay = dbWriteBulkhead.execute(() -> transactionService.startIdempotencyOrReplay(
                token.attemptId(),
                requestHash,
                admission.admissionId()
        ));
        if (replay.isPresent()) {
            return replay.get();
        }

        PaymentPreparationResult preparation = dbWriteBulkhead.execute(() -> transactionService.createHeldAndPayment(
                admission.admissionId(),
                token.attemptId(),
                command
        ));
        if (preparation.status() == PaymentPreparationResult.Status.WAITING) {
            return dbWriteBulkhead.execute(() -> transactionService.waiting(token.attemptId(), admission.admissionId()));
        }
        if (preparation.status() == PaymentPreparationResult.Status.TERMINAL_ADMISSION) {
            return dbWriteBulkhead.execute(() -> transactionService.terminalAdmission(
                    token.attemptId(),
                    admission.admissionId()
            ));
        }
        if (preparation.status() == PaymentPreparationResult.Status.EXISTING_IN_PROGRESS) {
            return dbWriteBulkhead.execute(() -> transactionService.currentState(token.attemptId()));
        }

        String providerOrderId = token.attemptId();
        if (!dbWriteBulkhead.execute(() -> transactionService.markPaymentConfirming(token.attemptId()))) {
            return dbWriteBulkhead.execute(() -> transactionService.currentState(token.attemptId()));
        }
        long reservationId = preparation.reservationId();
        PaymentConfirmResult payment = paymentCallGuard.confirm(providerOrderId, token.attemptId(), command);
        if (payment.status() == PaymentConfirmStatus.SUCCESS) {
            return dbWriteBulkhead.execute(() -> transactionService.confirm(
                    token.attemptId(),
                    reservationId,
                    payment.providerPaymentId()
            ));
        }
        if (payment.status() == PaymentConfirmStatus.FAILURE) {
            return dbWriteBulkhead.execute(() -> transactionService.fail(
                    token.attemptId(),
                    reservationId,
                    payment.errorCode()
            ));
        }
        return dbWriteBulkhead.execute(() -> transactionService.unknown(
                token.attemptId(),
                reservationId,
                payment.providerPaymentId()
        ));
    }

    public long saleEventId() {
        return properties.saleEventId();
    }

    private BookingResult rejectedAdmission(String attemptId, GateMode gateMode) {
        if (gateMode == GateMode.REDIS_FAILOVER_PAUSED) {
            return new BookingResult(
                    503,
                    BookingResult.ADMISSION_TEMPORARILY_UNAVAILABLE,
                    attemptId,
                    null,
                    null,
                    null,
                    true,
                    "RETRY_AFTER_SHORT_PAUSE",
                    "Redis failover is in progress"
            );
        }
        return new BookingResult(
                429,
                "ADMISSION_REJECTED",
                attemptId,
                null,
                null,
                null,
                true,
                "TRY_LATER_OR_SOLD_OUT",
                "Candidate pool is closed"
        );
    }

    private ProductSummary productSummary(long productId) {
        if (productCacheTtl.isZero() || productCacheTtl.isNegative()) {
            return loadProduct(productId);
        }
        long now = System.nanoTime();
        CachedProductSummary cached = productCache.get(productId);
        if (cached != null && cached.expiresAtNanos() > now) {
            return cached.product();
        }
        return productCache.compute(productId, (ignored, existing) -> {
            long current = System.nanoTime();
            if (existing != null && existing.expiresAtNanos() > current) {
                return existing;
            }
            return new CachedProductSummary(loadProduct(productId), current + productCacheTtl.toNanos());
        }).product();
    }

    private ProductSummary loadProduct(long productId) {
        return dbWriteBulkhead.execute(() -> repository.findProduct(productId))
                .orElseThrow(() -> new BusinessException(BookingErrorCode.PRODUCT_NOT_FOUND));
    }

    private record CachedProductSummary(ProductSummary product, long expiresAtNanos) {
    }
}
