package com.peakbooking.booking.application;

import com.peakbooking.booking.config.BookingProperties;
import com.peakbooking.booking.domain.AdmissionDecision;
import com.peakbooking.booking.domain.AdmissionResult;
import com.peakbooking.booking.domain.BookingErrorCode;
import com.peakbooking.booking.domain.CombinationPolicy;
import com.peakbooking.booking.infrastructure.jdbc.BookingJdbcRepository;
import com.peakbooking.booking.payment.PaymentConfirmResult;
import com.peakbooking.booking.payment.PaymentConfirmStatus;
import com.peakbooking.booking.payment.PaymentProvider;
import com.peakbooking.common.exception.BusinessException;
import java.util.Optional;
import org.springframework.stereotype.Service;

@Service
public class BookingApplicationService {

    private final BookingProperties properties;
    private final BookingJdbcRepository repository;
    private final AttemptTokenService attemptTokenService;
    private final CanonicalRequestHashCalculator requestHashCalculator;
    private final CombinationPolicy combinationPolicy;
    private final BookingAdmissionService admissionService;
    private final BookingTransactionService transactionService;
    private final PaymentProvider paymentProvider;

    public BookingApplicationService(
            BookingProperties properties,
            BookingJdbcRepository repository,
            AttemptTokenService attemptTokenService,
            CanonicalRequestHashCalculator requestHashCalculator,
            CombinationPolicy combinationPolicy,
            BookingAdmissionService admissionService,
            BookingTransactionService transactionService,
            PaymentProvider paymentProvider
    ) {
        this.properties = properties;
        this.repository = repository;
        this.attemptTokenService = attemptTokenService;
        this.requestHashCalculator = requestHashCalculator;
        this.combinationPolicy = combinationPolicy;
        this.admissionService = admissionService;
        this.transactionService = transactionService;
        this.paymentProvider = paymentProvider;
    }

    public BookingResult book(BookingCommand command) {
        ProductSummary product = repository.findProduct(command.productId())
                .orElseThrow(() -> new BusinessException(BookingErrorCode.PRODUCT_NOT_FOUND));
        combinationPolicy.validate(command.paymentPlan(), product.priceAmount());

        AttemptToken token = attemptTokenService.verify(
                command.bookingAttemptToken(),
                command.userId(),
                command.saleEventId(),
                command.productId()
        );
        String requestHash = requestHashCalculator.hash(command, token.attemptId());

        Optional<BookingResult> replayBeforeAdmission = transactionService.replayExisting(
                token.attemptId(),
                requestHash
        );
        if (replayBeforeAdmission.isPresent()) {
            return replayBeforeAdmission.get();
        }

        AdmissionDecision admission = transactionService.existingAdmission(token.attemptId())
                .orElseGet(() -> admissionService.admit(
                        command.saleEventId(),
                        command.productId(),
                        command.userId()
                ));
        if (admission.result() == AdmissionResult.REJECTED) {
            return new BookingResult(
                    429,
                    "ADMISSION_REJECTED",
                    token.attemptId(),
                    null,
                    null,
                    null,
                    true,
                    "TRY_LATER_OR_SOLD_OUT",
                    "Candidate pool is closed"
            );
        }

        Optional<BookingResult> replay = transactionService.startIdempotencyOrReplay(
                token.attemptId(),
                requestHash,
                admission.admissionId()
        );
        if (replay.isPresent()) {
            return replay.get();
        }

        Optional<Long> reservationId = transactionService.createHeldAndPayment(
                admission.admissionId(),
                token.attemptId(),
                command
        );
        if (reservationId.isEmpty()) {
            return transactionService.waiting(token.attemptId(), admission.admissionId());
        }

        PaymentConfirmResult payment = paymentProvider.confirm(token.attemptId(), command);
        if (payment.status() == PaymentConfirmStatus.SUCCESS) {
            return transactionService.confirm(token.attemptId(), reservationId.get(), payment.providerPaymentId());
        }
        if (payment.status() == PaymentConfirmStatus.FAILURE) {
            return transactionService.fail(token.attemptId(), reservationId.get(), payment.errorCode());
        }
        return transactionService.unknown(token.attemptId(), reservationId.get(), payment.providerPaymentId());
    }

    public long saleEventId() {
        return properties.saleEventId();
    }
}
