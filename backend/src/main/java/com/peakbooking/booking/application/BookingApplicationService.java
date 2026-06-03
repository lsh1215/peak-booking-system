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
import com.peakbooking.booking.payment.PaymentExecutionPlan;
import com.peakbooking.booking.payment.PaymentProcessorRegistry;
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
    private final LocalWaitingRoom localWaitingRoom;
    private final BookingTransactionService transactionService;
    private final BookingDbWriteBulkhead dbWriteBulkhead;
    private final PaymentCallGuard paymentCallGuard;
    private final PaymentProcessorRegistry paymentProcessorRegistry;
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
            LocalWaitingRoom localWaitingRoom,
            BookingTransactionService transactionService,
            BookingDbWriteBulkhead dbWriteBulkhead,
            PaymentCallGuard paymentCallGuard,
            PaymentProcessorRegistry paymentProcessorRegistry,
            Clock clock,
            @Value("${peak-booking.product-cache-ttl:1s}") Duration productCacheTtl
    ) {
        this.properties = properties;
        this.repository = repository;
        this.attemptTokenService = attemptTokenService;
        this.requestHashCalculator = requestHashCalculator;
        this.combinationPolicy = combinationPolicy;
        this.admissionService = admissionService;
        this.localWaitingRoom = localWaitingRoom;
        this.transactionService = transactionService;
        this.dbWriteBulkhead = dbWriteBulkhead;
        this.paymentCallGuard = paymentCallGuard;
        this.paymentProcessorRegistry = paymentProcessorRegistry;
        this.clock = clock;
        this.productCacheTtl = productCacheTtl;
    }

    public BookingResult book(BookingCommand command) {
        return doBook(command, false);
    }

    BookingResult processLocalQueued(BookingCommand command) {
        return doBook(command, true);
    }

    public BookingResult status(String bookingAttemptId) {
        return localWaitingRoom.status(bookingAttemptId)
                .orElseGet(() -> dbWriteBulkhead.execute(() -> transactionService.currentState(bookingAttemptId)));
    }

    private BookingResult doBook(BookingCommand command, boolean localQueueWorker) {
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
        PaymentExecutionPlan paymentExecutionPlan = paymentProcessorRegistry.plan(
                command.paymentPlan(),
                token.attemptId()
        );

        if (!localQueueWorker && localWaitingRoom.shouldPreferLocalQueue()) {
            return enqueueLocal(command, token.attemptId(), requestHash);
        }

        AdmissionDecision admission = localQueueWorker
                ? admissionService.admitFromLocalQueue(
                        command.saleEventId(),
                        command.productId(),
                        command.userId(),
                        token.attemptId()
                )
                : admissionService.admit(
                        command.saleEventId(),
                        command.productId(),
                        command.userId(),
                        token.attemptId()
                );
        if (admission.result() == AdmissionResult.WAITING_ROOM) {
            return waitingRoom(token.attemptId());
        }
        if (admission.result() == AdmissionResult.REJECTED) {
            if (!localQueueWorker && admission.gateMode() == GateMode.REDIS_FAILOVER_PAUSED) {
                Optional<BookingResult> replay = dbWriteBulkhead.execute(() ->
                        transactionService.replayExisting(token.attemptId(), requestHash)
                );
                if (replay.isPresent()) {
                    return replay.get();
                }
                return enqueueLocal(command, token.attemptId(), requestHash);
            }
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
                command,
                paymentExecutionPlan
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
        if (preparation.status() == PaymentPreparationResult.Status.FAILED) {
            releaseActiveCandidateAfterTerminalFailure(command, admission, preparation.terminalResult());
            return preparation.terminalResult();
        }

        long reservationId = preparation.reservationId();
        if (!preparation.requiresExternalConfirmation()) {
            return dbWriteBulkhead.execute(() -> transactionService.confirm(
                    token.attemptId(),
                    reservationId,
                    null
            ));
        }

        String providerOrderId = preparation.externalComponent().providerOrderId();
        if (!dbWriteBulkhead.execute(() -> transactionService.markPaymentConfirming(token.attemptId()))) {
            return dbWriteBulkhead.execute(() -> transactionService.currentState(token.attemptId()));
        }
        PaymentConfirmResult payment = paymentCallGuard.confirm(providerOrderId, token.attemptId(), command);
        if (payment.status() == PaymentConfirmStatus.SUCCESS) {
            return dbWriteBulkhead.execute(() -> transactionService.confirm(
                    token.attemptId(),
                    reservationId,
                    payment.providerPaymentId()
            ));
        }
        if (payment.status() == PaymentConfirmStatus.FAILURE) {
            BookingResult failed = dbWriteBulkhead.execute(() -> transactionService.fail(
                    token.attemptId(),
                    reservationId,
                    payment.errorCode()
            ));
            releaseActiveCandidateAfterTerminalFailure(command, admission, failed);
            return failed;
        }
        return dbWriteBulkhead.execute(() -> transactionService.unknown(
                token.attemptId(),
                reservationId,
                payment.providerPaymentId()
        ));
    }

    private BookingResult enqueueLocal(BookingCommand command, String attemptId, String requestHash) {
        LocalQueueSubmission submission = localWaitingRoom.enqueue(command, attemptId, requestHash);
        return switch (submission.status()) {
            case ACCEPTED, ALREADY_ACCEPTED -> localQueued(attemptId, submission.queuePosition());
            case ALREADY_COMPLETED -> submission.completedResult();
            case CONFLICT -> throw new BusinessException(BookingErrorCode.IDEMPOTENCY_CONFLICT);
            case FULL -> localQueueFull(attemptId);
            case DISABLED -> new BookingResult(
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
        };
    }

    private BookingResult localQueued(String attemptId, int queuePosition) {
        return new BookingResult(
                202,
                BookingResult.LOCAL_QUEUE_ACCEPTED,
                attemptId,
                null,
                null,
                null,
                true,
                "POLL_BOOKING_STATUS",
                "Accepted in local waiting room. Approximate local position: " + queuePosition
        );
    }

    private BookingResult localQueueFull(String attemptId) {
        return new BookingResult(
                429,
                BookingResult.LOCAL_QUEUE_FULL,
                attemptId,
                null,
                null,
                null,
                true,
                "RETRY_AFTER_SHORT_PAUSE",
                "Local waiting room is full"
        );
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

    private BookingResult waitingRoom(String attemptId) {
        return new BookingResult(
                202,
                "WAITING_ROOM",
                attemptId,
                null,
                null,
                null,
                true,
                "RETRY_POST_BOOKINGS",
                "Waiting for an active candidate slot"
        );
    }

    private void releaseActiveCandidateAfterTerminalFailure(
            BookingCommand command,
            AdmissionDecision admission,
            BookingResult result
    ) {
        if (!"PAYMENT_FAILED".equals(result.businessCode())
                && !"POINTS_NOT_ENOUGH".equals(result.businessCode())
                && !"WAITING_EXPIRED".equals(result.businessCode())
                && !"RESERVATION_RELEASED".equals(result.businessCode())) {
            return;
        }
        admissionService.releaseActiveCandidate(
                command.saleEventId(),
                command.productId(),
                command.userId(),
                admission.redisSeq(),
                result.businessCode()
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
