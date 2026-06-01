package com.peakbooking.booking.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.peakbooking.booking.application.AttemptTokenService;
import com.peakbooking.booking.application.BookingApplicationService;
import com.peakbooking.booking.application.BookingCommand;
import com.peakbooking.booking.application.BookingResult;
import com.peakbooking.booking.application.CanonicalRequestHashCalculator;
import com.peakbooking.booking.application.CheckoutApplicationService;
import com.peakbooking.booking.application.MockPgScenario;
import com.peakbooking.booking.domain.BookingErrorCode;
import com.peakbooking.booking.domain.PaymentMethodType;
import com.peakbooking.booking.domain.PaymentPlan;
import com.peakbooking.booking.domain.PaymentPlanLine;
import com.peakbooking.booking.infrastructure.jpa.BookingJpaRepository;
import com.peakbooking.booking.infrastructure.persistence.InventorySnapshot;
import com.peakbooking.booking.infrastructure.persistence.ReservationRecord;
import com.peakbooking.booking.payment.MockPaymentProvider;
import com.peakbooking.booking.payment.PaymentStatus;
import com.peakbooking.common.exception.BusinessException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(properties = "peak-booking.candidate-limit=30")
@ActiveProfiles("test")
class BookingFlowIntegrationTest extends BookingIntegrationTestSupport {

    @Autowired
    private BookingApplicationService bookingApplicationService;

    @Autowired
    private AttemptTokenService attemptTokenService;

    @Autowired
    private BookingJpaRepository repository;

    @Autowired
    private MockPaymentProvider mockPaymentProvider;

    @Autowired
    private CheckoutApplicationService checkoutApplicationService;

    @Autowired
    private CanonicalRequestHashCalculator requestHashCalculator;

    @Autowired
    private Clock clock;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Test
    void should_never_confirm_more_than_stock_when_concurrent_booking_requests() throws Exception {
        seedProductAndInventory(10);
        for (long userId = 1; userId <= 20; userId++) {
            seedPoints(userId, 0);
        }

        try (var executor = Executors.newFixedThreadPool(12)) {
            List<Callable<BookingResult>> tasks = java.util.stream.LongStream.rangeClosed(1, 20)
                    .mapToObj(userId -> (Callable<BookingResult>) () -> bookOrBusy(command(userId)))
                    .toList();
            List<BookingResult> results = executor.invokeAll(tasks).stream()
                    .map(future -> {
                        try {
                            return future.get();
                        } catch (Exception e) {
                            throw new RuntimeException(e);
                        }
                    })
                    .toList();

            assertThat(results.stream().filter(result -> "BOOKING_CONFIRMED".equals(result.businessCode())).count())
                    .isLessThanOrEqualTo(10);
            InventorySnapshot inventory = repository.inventory(1, 1);
            assertThat(inventory.confirmedCount()).isLessThanOrEqualTo(10);
            assertThat(inventory.occupiedCount()).isLessThanOrEqualTo(10);
        }
    }

    @Test
    void should_replay_duplicate_attempt_without_second_pg_confirm() {
        seedProductAndInventory(10);
        seedPoints(1, 0);
        BookingCommand command = command(1);

        BookingResult first = bookingApplicationService.book(command);
        BookingResult second = bookingApplicationService.book(command);

        String attemptId = attemptTokenService.verify(command.bookingAttemptToken(), 1, 1, 1).attemptId();
        assertThat(first.businessCode()).isEqualTo("BOOKING_CONFIRMED");
        assertThat(second).isEqualTo(first);
        assertThat(mockPaymentProvider.confirmCount(attemptId)).isEqualTo(1);
    }

    @Test
    void should_allow_only_one_pg_confirm_owner_for_same_attempt_concurrent_posts() throws Exception {
        seedProductAndInventory(10);
        seedPoints(1, 0);
        String token = attemptTokenService.issue(1, 1, 1).rawToken();
        BookingCommand command = commandWithPlan(1, token, List.of(
                new PaymentPlanLine(PaymentMethodType.CREDIT_CARD, 10000)
        ), MockPgScenario.SUCCESS);
        String attemptId = attemptTokenService.verify(token, 1, 1, 1).attemptId();

        try (var executor = Executors.newFixedThreadPool(12)) {
            List<Callable<BookingResult>> tasks = java.util.stream.IntStream.range(0, 20)
                    .mapToObj(ignored -> (Callable<BookingResult>) () -> bookingApplicationService.book(command))
                    .toList();
            executor.invokeAll(tasks);
        }

        assertThat(mockPaymentProvider.confirmCount(attemptId)).isEqualTo(1);
        assertThat(count("reservation")).isEqualTo(1);
        assertThat(count("payment_attempt")).isEqualTo(1);
    }

    @Test
    void should_not_mark_same_attempt_as_waiting_when_duplicate_races_near_last_stock() throws Exception {
        seedProductAndInventory(1);
        seedPoints(1, 0);
        String token = attemptTokenService.issue(1, 1, 1).rawToken();
        BookingCommand command = commandWithPlan(1, token, List.of(
                new PaymentPlanLine(PaymentMethodType.CREDIT_CARD, 10000)
        ), MockPgScenario.TIMEOUT);

        try (var executor = Executors.newFixedThreadPool(12)) {
            List<Callable<BookingResult>> tasks = java.util.stream.IntStream.range(0, 20)
                    .mapToObj(ignored -> (Callable<BookingResult>) () -> bookingApplicationService.book(command))
                    .toList();
            executor.invokeAll(tasks);
        }

        assertThat(count("booking_admission")).isEqualTo(1);
        assertThat(count("reservation")).isEqualTo(1);
        assertThat(count("payment_attempt")).isEqualTo(1);
        String admissionStatus = jdbcTemplate.queryForObject(
                "SELECT status FROM booking_admission WHERE user_id = 1",
                String.class
        );
        assertThat(admissionStatus).isEqualTo("ADMITTED");
    }

    @Test
    void should_resume_pre_reservation_idempotency_after_same_attempt_retry() {
        seedProductAndInventory(10);
        seedPoints(1, 0);
        String token = attemptTokenService.issue(1, 1, 1).rawToken();
        String attemptId = attemptTokenService.verify(token, 1, 1, 1).attemptId();
        BookingCommand command = commandWithPlan(1, token, List.of(
                new PaymentPlanLine(PaymentMethodType.CREDIT_CARD, 10000)
        ), MockPgScenario.SUCCESS);
        seedAdmittedAttemptWithoutReservation(attemptId, 1);
        jdbcTemplate.update(
                """
                        INSERT INTO idempotency_record (booking_attempt_id, request_hash, status, expires_at)
                        VALUES (?, ?, 'IN_PROGRESS', DATE_ADD(NOW(6), INTERVAL 1 DAY))
                        """,
                attemptId,
                requestHashCalculator.hash(command, attemptId)
        );

        BookingResult result = bookingApplicationService.book(command);

        assertThat(result.businessCode()).isEqualTo("BOOKING_CONFIRMED");
        assertThat(count("reservation")).isEqualTo(1);
        assertThat(count("payment_attempt")).isEqualTo(1);
        assertThat(mockPaymentProvider.confirmCount(attemptId)).isEqualTo(1);
    }

    @Test
    void should_not_create_non_owner_idempotency_when_fresh_token_posts_against_active_attempt() {
        seedProductAndInventory(10);
        seedPoints(1, 0);
        BookingCommand activeCommand = commandWithScenario(1, MockPgScenario.TIMEOUT);
        String activeAttemptId = attemptTokenService.verify(activeCommand.bookingAttemptToken(), 1, 1, 1).attemptId();
        BookingResult active = bookingApplicationService.book(activeCommand);
        BookingCommand freshCommand = commandWithScenario(1, MockPgScenario.SUCCESS);
        String freshAttemptId = attemptTokenService.verify(freshCommand.bookingAttemptToken(), 1, 1, 1).attemptId();

        BookingResult replay = bookingApplicationService.book(freshCommand);

        assertThat(active.businessCode()).isEqualTo("PAYMENT_UNKNOWN");
        assertThat(replay.bookingAttemptId()).isEqualTo(activeAttemptId);
        assertThat(replay.businessCode()).isEqualTo("PAYMENT_UNKNOWN");
        assertThat(count("booking_admission")).isEqualTo(1);
        assertThat(count("reservation")).isEqualTo(1);
        assertThat(count("payment_attempt")).isEqualTo(1);
        assertThat(countBy("idempotency_record", "booking_attempt_id", freshAttemptId)).isZero();
        String admissionStatus = jdbcTemplate.queryForObject(
                "SELECT status FROM booking_admission WHERE user_id = 1",
                String.class
        );
        assertThat(admissionStatus).isEqualTo("ADMITTED");
    }

    @Test
    void should_expire_waiting_candidate_when_fresh_token_posts_after_waiting_window() throws Exception {
        seedProductAndInventory(1);
        seedPoints(1, 0);
        seedPoints(2, 0);
        bookingApplicationService.book(commandWithScenario(1, MockPgScenario.TIMEOUT));
        BookingResult waiting = bookingApplicationService.book(commandWithScenario(2, MockPgScenario.SUCCESS));
        BookingCommand freshCommand = commandWithScenario(2, MockPgScenario.SUCCESS);
        String freshAttemptId = attemptTokenService.verify(freshCommand.bookingAttemptToken(), 2, 1, 1).attemptId();

        Thread.sleep(350);
        BookingResult expired = bookingApplicationService.book(freshCommand);

        assertThat(waiting.businessCode()).isEqualTo("WAITING_CANDIDATE");
        assertThat(expired.businessCode()).isEqualTo("WAITING_EXPIRED");
        assertThat(countBy("idempotency_record", "booking_attempt_id", freshAttemptId)).isZero();
        String admissionStatus = jdbcTemplate.queryForObject(
                "SELECT status FROM booking_admission WHERE user_id = 2",
                String.class
        );
        assertThat(admissionStatus).isEqualTo("WAITING_EXPIRED");
    }

    @Test
    void should_pick_single_canonical_owner_for_concurrent_fresh_tokens() throws Exception {
        seedProductAndInventory(10);
        seedPoints(1, 0);
        String tokenA = attemptTokenService.issue(1, 1, 1).rawToken();
        String tokenB = attemptTokenService.issue(1, 1, 1).rawToken();
        BookingCommand commandA = commandWithPlan(1, tokenA, List.of(
                new PaymentPlanLine(PaymentMethodType.CREDIT_CARD, 10000)
        ), MockPgScenario.SUCCESS);
        BookingCommand commandB = commandWithPlan(1, tokenB, List.of(
                new PaymentPlanLine(PaymentMethodType.CREDIT_CARD, 10000)
        ), MockPgScenario.SUCCESS);

        try (var executor = Executors.newFixedThreadPool(2)) {
            executor.invokeAll(List.of(
                    (Callable<BookingResult>) () -> bookingApplicationService.book(commandA),
                    (Callable<BookingResult>) () -> bookingApplicationService.book(commandB)
            ));
        }

        assertThat(count("booking_admission")).isEqualTo(1);
        assertThat(count("reservation")).isEqualTo(1);
        assertThat(count("payment_attempt")).isEqualTo(1);
        Integer totalConfirmCount = jdbcTemplate.queryForObject(
                "SELECT COALESCE(SUM(confirm_count), 0) FROM mock_pg_payment",
                Integer.class
        );
        assertThat(totalConfirmCount).isLessThanOrEqualTo(1);
    }

    @Test
    void should_reject_booking_before_sale_open_at() {
        seedFutureProductAndInventory(10);
        seedPoints(1, 0);

        assertThatThrownBy(() -> bookingApplicationService.book(command(1)))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(BookingErrorCode.SALE_NOT_OPEN));
        assertThat(count("booking_admission")).isZero();
        assertThat(count("reservation")).isZero();
    }

    @Test
    void should_reject_same_attempt_when_request_hash_changes() {
        seedProductAndInventory(10);
        seedPoints(1, 5000);
        String token = attemptTokenService.issue(1, 1, 1).rawToken();

        bookingApplicationService.book(commandWithPlan(1, token, List.of(
                new PaymentPlanLine(PaymentMethodType.CREDIT_CARD, 9000),
                new PaymentPlanLine(PaymentMethodType.Y_POINT, 1000)
        ), MockPgScenario.SUCCESS));

        assertThatThrownBy(() -> bookingApplicationService.book(commandWithPlan(1, token, List.of(
                new PaymentPlanLine(PaymentMethodType.CREDIT_CARD, 8000),
                new PaymentPlanLine(PaymentMethodType.Y_POINT, 2000)
        ), MockPgScenario.SUCCESS)))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void should_release_payment_unknown_inventory_after_deadline_and_cancel_late_success() throws Exception {
        seedProductAndInventory(10);
        seedPoints(1, 0);
        BookingCommand command = commandWithScenario(1, MockPgScenario.LATE_SUCCESS);

        BookingResult pending = bookingApplicationService.book(command);
        assertThat(pending.businessCode()).isEqualTo("PAYMENT_UNKNOWN");

        Thread.sleep(120);
        int recovered = recoverDueReservations();

        InventorySnapshot inventory = repository.inventory(1, 1);
        assertThat(recovered).isGreaterThanOrEqualTo(1);
        assertThat(inventory.occupiedCount()).isZero();
        Integer cancelled = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM payment_attempt WHERE status = 'CANCELLED_AFTER_RELEASE'",
                Integer.class
        );
        assertThat(cancelled).isEqualTo(1);
    }

    @Test
    void should_recover_confirming_held_by_querying_pg_before_deadline() {
        seedProductAndInventory(1);
        seedConfirmingHeldAttempt("ba_confirming_before_deadline", now().plusSeconds(5), "APPROVED");

        int recovered = recoverDueReservations();

        assertThat(recovered).isGreaterThanOrEqualTo(1);
        InventorySnapshot inventory = repository.inventory(1, 1);
        assertThat(inventory.confirmedCount()).isEqualTo(1);
        assertThat(inventory.occupiedCount()).isEqualTo(1);
        String paymentStatus = jdbcTemplate.queryForObject(
                "SELECT status FROM payment_attempt WHERE booking_attempt_id = 'ba_confirming_before_deadline'",
                String.class
        );
        assertThat(paymentStatus).isEqualTo("CONFIRMED");
    }

    @Test
    void should_not_claim_live_confirming_payment_before_recovery_grace() {
        seedProductAndInventory(1);
        seedConfirmingHeldAttempt("ba_live_confirming", now().plusSeconds(5), "APPROVED");
        jdbcTemplate.update(
                """
                        UPDATE payment_attempt
                        SET next_reconcile_at = DATE_ADD(NOW(6), INTERVAL 2 SECOND)
                        WHERE booking_attempt_id = 'ba_live_confirming'
                        """
        );

        int recovered = recoverDueReservations();

        assertThat(recovered).isZero();
        String reservationStatus = jdbcTemplate.queryForObject(
                "SELECT status FROM reservation WHERE booking_attempt_id = 'ba_live_confirming'",
                String.class
        );
        String paymentStatus = jdbcTemplate.queryForObject(
                "SELECT status FROM payment_attempt WHERE booking_attempt_id = 'ba_live_confirming'",
                String.class
        );
        assertThat(reservationStatus).isEqualTo("HELD");
        assertThat(paymentStatus).isEqualTo("CONFIRMING");
    }

    @Test
    void should_release_and_cancel_confirming_held_when_pg_success_arrives_after_deadline() {
        seedProductAndInventory(1);
        seedConfirmingHeldAttempt("ba_confirming_after_deadline", now().minusSeconds(1), "APPROVED");

        int recovered = recoverDueReservations();

        assertThat(recovered).isGreaterThanOrEqualTo(1);
        InventorySnapshot inventory = repository.inventory(1, 1);
        assertThat(inventory.occupiedCount()).isZero();
        String paymentStatus = jdbcTemplate.queryForObject(
                "SELECT status FROM payment_attempt WHERE booking_attempt_id = 'ba_confirming_after_deadline'",
                String.class
        );
        assertThat(paymentStatus).isEqualTo("CANCELLED_AFTER_RELEASE");
    }

    @Test
    void should_release_expired_confirming_inventory_before_slow_pg_query_finishes() throws Exception {
        seedProductAndInventory(1);
        seedConfirmingHeldAttemptWithScenario(
                "ba_expired_confirming_timeout",
                now().minusSeconds(1),
                "TIMEOUT",
                "UNKNOWN"
        );

        try (var executor = Executors.newSingleThreadExecutor()) {
            var future = executor.submit(() -> recoveryWorkerService.recoverDueReservations());

            assertOccupiedCountEventuallyZero();
            assertThat(future.get()).isGreaterThanOrEqualTo(1);
        }
        Integer activeWindowCount = jdbcTemplate.queryForObject(
                """
                        SELECT COUNT(*)
                        FROM payment_attempt
                        WHERE booking_attempt_id = 'ba_expired_confirming_timeout'
                          AND active_reconcile_until IS NOT NULL
                        """,
                Integer.class
        );
        assertThat(activeWindowCount).isEqualTo(1);
    }

    @Test
    void should_continue_payment_reconciliation_after_inventory_release() {
        seedProductAndInventory(1);
        seedReleasedReconcilingAttempt("ba_released_late_success", "APPROVED");

        int recovered = recoverDueReservations();

        assertThat(recovered).isGreaterThanOrEqualTo(1);
        InventorySnapshot inventory = repository.inventory(1, 1);
        assertThat(inventory.occupiedCount()).isZero();
        String paymentStatus = jdbcTemplate.queryForObject(
                "SELECT status FROM payment_attempt WHERE booking_attempt_id = 'ba_released_late_success'",
                String.class
        );
        assertThat(paymentStatus).isEqualTo("CANCELLED_AFTER_RELEASE");
    }

    @Test
    void should_not_create_durable_write_path_rows_when_entering_checkout() {
        seedProductAndInventory(10);
        seedPoints(1, 1000);

        CheckoutApplicationService.CheckoutResult result = checkoutApplicationService.checkout(1, 1);

        assertThat(result.bookingAttemptId()).isNotBlank();
        assertThat(count("booking_admission")).isZero();
        assertThat(count("idempotency_record")).isZero();
        assertThat(count("reservation")).isZero();
        assertThat(count("payment_attempt")).isZero();
    }

    @Test
    void should_reuse_active_attempt_when_checkout_is_reopened() {
        seedProductAndInventory(10);
        seedPoints(1, 0);
        BookingResult active = bookingApplicationService.book(commandWithScenario(1, MockPgScenario.TIMEOUT));

        CheckoutApplicationService.CheckoutResult checkout = checkoutApplicationService.checkout(1, 1);
        String checkoutAttemptId = attemptTokenService.verify(checkout.bookingAttemptId(), 1, 1, 1).attemptId();

        assertThat(active.businessCode()).isEqualTo("PAYMENT_UNKNOWN");
        assertThat(checkoutAttemptId).isEqualTo(active.bookingAttemptId());
        assertThat(count("booking_admission")).isEqualTo(1);
        assertThat(count("reservation")).isEqualTo(1);
        assertThat(count("payment_attempt")).isEqualTo(1);
    }

    @Test
    void should_keep_db_fallback_sticky_for_same_sale_event_even_when_redis_is_available() {
        seedProductAndInventory(10);
        seedPoints(1, 0);
        markDbFallback();

        BookingResult result = bookingApplicationService.book(command(1));

        assertThat(result.businessCode()).isEqualTo("BOOKING_CONFIRMED");
        String gateMode = jdbcTemplate.queryForObject(
                "SELECT gate_mode FROM booking_admission WHERE user_id = 1",
                String.class
        );
        assertThat(gateMode).isEqualTo("DB_FALLBACK");
    }

    @Test
    void should_not_consume_candidate_sequence_for_duplicate_db_fallback_admission() throws Exception {
        seedProductAndInventory(10);
        seedPoints(1, 0);
        markDbFallback();

        try (var executor = Executors.newFixedThreadPool(12)) {
            List<Callable<BookingResult>> tasks = java.util.stream.IntStream.range(0, 20)
                    .mapToObj(ignored -> (Callable<BookingResult>) () -> bookingApplicationService.book(command(1)))
                    .toList();
            executor.invokeAll(tasks);
        }

        Long nextSeq = jdbcTemplate.queryForObject(
                "SELECT next_seq FROM admission_sequence WHERE sale_event_id = 1 AND product_id = 1",
                Long.class
        );
        assertThat(nextSeq).isEqualTo(1);
        assertThat(count("booking_admission")).isEqualTo(1);
    }

    @Test
    void should_hold_y_points_only_once_for_same_attempt_concurrent_posts() throws Exception {
        seedProductAndInventory(10);
        seedPoints(1, 10_000);
        String token = attemptTokenService.issue(1, 1, 1).rawToken();
        BookingCommand command = commandWithPlan(1, token, List.of(
                new PaymentPlanLine(PaymentMethodType.CREDIT_CARD, 9_000),
                new PaymentPlanLine(PaymentMethodType.Y_POINT, 1_000)
        ), MockPgScenario.SUCCESS);

        try (var executor = Executors.newFixedThreadPool(12)) {
            List<Callable<BookingResult>> tasks = java.util.stream.IntStream.range(0, 20)
                    .mapToObj(ignored -> (Callable<BookingResult>) () -> bookingApplicationService.book(command))
                    .toList();
            executor.invokeAll(tasks);
        }

        Long availablePoints = jdbcTemplate.queryForObject(
                "SELECT available_points FROM point_account WHERE user_id = 1",
                Long.class
        );
        Long heldPoints = jdbcTemplate.queryForObject(
                "SELECT held_points FROM point_account WHERE user_id = 1",
                Long.class
        );
        assertThat(availablePoints).isEqualTo(9_000);
        assertThat(heldPoints).isZero();
        assertThat(count("point_hold")).isEqualTo(1);
    }

    @Test
    void should_not_reopen_terminal_admission_with_new_checkout_token() {
        seedProductAndInventory(1);
        seedPoints(1, 0);

        BookingResult confirmed = bookingApplicationService.book(command(1));
        BookingResult retryWithNewToken = bookingApplicationService.book(command(1));

        assertThat(confirmed.businessCode()).isEqualTo("BOOKING_CONFIRMED");
        assertThat(retryWithNewToken.businessCode()).isEqualTo("BOOKING_CONFIRMED");
        String admissionStatus = jdbcTemplate.queryForObject(
                "SELECT status FROM booking_admission WHERE user_id = 1",
                String.class
        );
        assertThat(admissionStatus).isEqualTo("SUCCEEDED");
        assertThat(count("reservation")).isEqualTo(1);
    }

    @Test
    void should_not_confirm_held_reservation_after_hold_deadline() {
        seedProductAndInventory(1);
        seedHeldAttempt("ba_expired_held", now().minusSeconds(1), "REQUESTED");
        ReservationRecord reservation = repository.findReservationByAttempt("ba_expired_held").orElseThrow();

        boolean confirmed = transactionTemplate.execute(status -> repository.confirmReservation(reservation, now()));

        assertThat(confirmed).isFalse();
        InventorySnapshot inventory = repository.inventory(1, 1);
        assertThat(inventory.confirmedCount()).isZero();
        assertThat(inventory.reservedCount()).isEqualTo(1);
    }

    @Test
    void should_mark_payment_manual_review_after_active_reconciliation_window() throws Exception {
        seedProductAndInventory(10);
        seedPoints(1, 0);
        BookingCommand command = commandWithScenario(1, MockPgScenario.TIMEOUT);

        BookingResult pending = bookingApplicationService.book(command);
        assertThat(pending.businessCode()).isEqualTo("PAYMENT_UNKNOWN");

        Thread.sleep(120);
        recoveryWorkerService.recoverDueReservations();
        jdbcTemplate.update(
                """
                        UPDATE payment_attempt
                        SET status = 'RECONCILING_AFTER_RELEASE',
                            active_reconcile_until = DATE_SUB(NOW(6), INTERVAL 1 SECOND)
                        WHERE booking_attempt_id = ?
                        """,
                pending.bookingAttemptId()
        );

        recoveryWorkerService.recoverDueReservations();

        String status = jdbcTemplate.queryForObject(
                "SELECT status FROM payment_attempt WHERE booking_attempt_id = ?",
                String.class,
                pending.bookingAttemptId()
        );
        assertThat(status).isEqualTo("MANUAL_REVIEW_REQUIRED");
    }

    @Test
    void should_replay_payment_unknown_without_new_admission() {
        seedProductAndInventory(10);
        seedPoints(1, 0);
        BookingCommand command = commandWithScenario(1, MockPgScenario.TIMEOUT);

        BookingResult pending = bookingApplicationService.book(command);
        int admissions = count("booking_admission");

        BookingResult replay = bookingApplicationService.book(command);

        assertThat(pending.businessCode()).isEqualTo("PAYMENT_UNKNOWN");
        assertThat(replay.businessCode()).isEqualTo("PAYMENT_UNKNOWN");
        assertThat(replay.reservationStatus()).isEqualTo("PAYMENT_UNKNOWN");
        assertThat(replay.paymentStatus()).isEqualTo("PAYMENT_UNKNOWN");
        assertThat(count("booking_admission")).isEqualTo(admissions);
    }

    @Test
    void should_return_unknown_when_mock_pg_order_is_missing() {
        assertThat(mockPaymentProvider.queryByOrderId("missing-order").status())
                .isEqualTo(PaymentStatus.UNKNOWN);
    }

    @Test
    void should_replay_waiting_candidate_without_new_admission() {
        seedProductAndInventory(1);
        seedPoints(1, 0);
        seedPoints(2, 0);
        bookingApplicationService.book(commandWithScenario(1, MockPgScenario.TIMEOUT));
        BookingCommand waitingCommand = commandWithScenario(2, MockPgScenario.SUCCESS);

        BookingResult waiting = bookingApplicationService.book(waitingCommand);
        int admissions = count("booking_admission");
        BookingResult replay = bookingApplicationService.book(waitingCommand);

        assertThat(waiting.businessCode()).isEqualTo("WAITING_CANDIDATE");
        assertThat(replay.businessCode()).isEqualTo("WAITING_CANDIDATE");
        assertThat(count("booking_admission")).isEqualTo(admissions);
    }

    @Test
    void should_promote_waiting_candidate_after_prior_unknown_releases() throws Exception {
        seedProductAndInventory(1);
        seedPoints(1, 0);
        seedPoints(2, 0);
        BookingCommand firstCommand = commandWithScenario(1, MockPgScenario.TIMEOUT);
        BookingCommand secondCommand = commandWithScenario(2, MockPgScenario.SUCCESS);

        BookingResult first = bookingApplicationService.book(firstCommand);
        BookingResult secondWaiting = bookingApplicationService.book(secondCommand);

        assertThat(first.businessCode()).isEqualTo("PAYMENT_UNKNOWN");
        assertThat(secondWaiting.businessCode()).isEqualTo("WAITING_CANDIDATE");

        Thread.sleep(120);
        recoveryWorkerService.recoverDueReservations();
        BookingResult secondConfirmed = bookingApplicationService.book(secondCommand);

        assertThat(secondConfirmed.businessCode()).isEqualTo("BOOKING_CONFIRMED");
        assertThat(repository.inventory(1, 1).confirmedCount()).isEqualTo(1);
        assertThat(repository.inventory(1, 1).occupiedCount()).isEqualTo(1);
    }

    @Test
    void should_promote_waiting_candidates_by_db_admission_sequence_not_retry_speed() throws Exception {
        seedProductAndInventory(1);
        seedPoints(1, 0);
        seedPoints(2, 0);
        seedPoints(3, 0);
        BookingCommand firstCommand = commandWithScenario(1, MockPgScenario.TIMEOUT);
        BookingCommand secondCommand = commandWithScenario(2, MockPgScenario.SUCCESS);
        BookingCommand thirdCommand = commandWithScenario(3, MockPgScenario.SUCCESS);

        bookingApplicationService.book(firstCommand);
        assertThat(bookingApplicationService.book(secondCommand).businessCode()).isEqualTo("WAITING_CANDIDATE");
        assertThat(bookingApplicationService.book(thirdCommand).businessCode()).isEqualTo("WAITING_CANDIDATE");
        jdbcTemplate.update(
                """
                        UPDATE booking_admission
                        SET waiting_expires_at = DATE_ADD(NOW(6), INTERVAL 1 SECOND)
                        WHERE user_id IN (2, 3)
                        """
        );

        Thread.sleep(120);
        recoveryWorkerService.recoverDueReservations();
        BookingResult thirdRetryFirst = bookingApplicationService.book(thirdCommand);
        BookingResult secondRetryAfter = bookingApplicationService.book(secondCommand);

        assertThat(thirdRetryFirst.businessCode()).isEqualTo("WAITING_CANDIDATE");
        assertThat(secondRetryAfter.businessCode()).isEqualTo("BOOKING_CONFIRMED");
    }

    @Test
    void should_not_give_new_admission_chance_after_waiting_expired() throws Exception {
        seedProductAndInventory(1);
        seedPoints(1, 0);
        seedPoints(2, 0);
        BookingCommand firstCommand = commandWithScenario(1, MockPgScenario.TIMEOUT);
        BookingCommand secondCommand = commandWithScenario(2, MockPgScenario.SUCCESS);

        bookingApplicationService.book(firstCommand);
        BookingResult secondWaiting = bookingApplicationService.book(secondCommand);

        assertThat(secondWaiting.businessCode()).isEqualTo("WAITING_CANDIDATE");

        Thread.sleep(350);
        BookingResult expired = bookingApplicationService.book(secondCommand);

        assertThat(expired.businessCode()).isEqualTo("WAITING_EXPIRED");
        assertThat(count("booking_admission")).isEqualTo(2);
        assertThat(count("reservation")).isEqualTo(1);
    }

    @Autowired
    private com.peakbooking.booking.application.RecoveryWorkerService recoveryWorkerService;

    private int recoverDueReservations() {
        return recoveryWorkerService.recoverDueReservations();
    }

    private int count(String tableName) {
        Integer value = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + tableName, Integer.class);
        return value == null ? 0 : value;
    }

    private int countBy(String tableName, String columnName, String value) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM " + tableName + " WHERE " + columnName + " = ?",
                Integer.class,
                value
        );
        return count == null ? 0 : count;
    }

    private BookingResult bookOrBusy(BookingCommand command) {
        try {
            return bookingApplicationService.book(command);
        } catch (BusinessException e) {
            if (e.getErrorCode() != BookingErrorCode.SERVICE_BUSY) {
                throw e;
            }
            return new BookingResult(
                    e.getErrorCode().getStatus().value(),
                    "SERVICE_BUSY",
                    null,
                    null,
                    null,
                    null,
                    true,
                    "TRY_LATER",
                    e.getMessage()
            );
        }
    }

    private LocalDateTime now() {
        return LocalDateTime.now(clock);
    }

    private void markDbFallback() {
        jdbcTemplate.update(
                """
                        INSERT INTO admission_sequence (sale_event_id, product_id, next_seq, gate_mode)
                        VALUES (1, 1, 0, 'DB_FALLBACK')
                        ON DUPLICATE KEY UPDATE gate_mode = 'DB_FALLBACK'
                        """
        );
    }

    private void seedFutureProductAndInventory(int totalStock) {
        jdbcTemplate.update(
                """
                        INSERT INTO product (
                            id, name, price_amount, currency, sale_open_at, check_in_at, check_out_at
                        )
                        VALUES (
                            1, 'Peak Room', 10000, 'KRW',
                            DATE_ADD(NOW(6), INTERVAL 1 DAY), NOW(6), DATE_ADD(NOW(6), INTERVAL 2 DAY)
                        )
                        """
        );
        jdbcTemplate.update(
                """
                        INSERT INTO sale_inventory (
                            sale_event_id, product_id, total_count, reserved_count, payment_unknown_count, confirmed_count
                        )
                        VALUES (1, 1, ?, 0, 0, 0)
                        """,
                totalStock
        );
    }

    private void seedConfirmingHeldAttempt(String bookingAttemptId, LocalDateTime holdExpiresAt, String pgStatus) {
        seedConfirmingHeldAttemptWithScenario(bookingAttemptId, holdExpiresAt, "LATE_SUCCESS", pgStatus);
    }

    private void seedConfirmingHeldAttemptWithScenario(
            String bookingAttemptId,
            LocalDateTime holdExpiresAt,
            String scenario,
            String pgStatus
    ) {
        seedHeldAttempt(bookingAttemptId, holdExpiresAt, "CONFIRMING");
        jdbcTemplate.update(
                """
                        INSERT INTO mock_pg_payment (
                            provider_order_id, provider_payment_id, scenario, status, confirm_count
                        )
                        VALUES (?, ?, ?, ?, 1)
                        """,
                bookingAttemptId,
                "mock_pg_" + bookingAttemptId,
                scenario,
                pgStatus
        );
    }

    private void seedReleasedReconcilingAttempt(String bookingAttemptId, String pgStatus) {
        jdbcTemplate.update(
                """
                        INSERT INTO admission_sequence (sale_event_id, product_id, next_seq, gate_mode)
                        VALUES (1, 1, 1, 'REDIS')
                        """
        );
        jdbcTemplate.update(
                """
                        INSERT INTO booking_admission (
                            sale_event_id, product_id, user_id, gate_mode, db_admission_seq,
                            candidate_rank, status, booking_attempt_id, admitted_at, completed_at
                        )
                        VALUES (1, 1, 99, 'REDIS', 1, 1, 'FAILED', ?, NOW(6), NOW(6))
                        """,
                bookingAttemptId
        );
        Long admissionId = jdbcTemplate.queryForObject(
                "SELECT id FROM booking_admission WHERE booking_attempt_id = ?",
                Long.class,
                bookingAttemptId
        );
        jdbcTemplate.update(
                """
                        INSERT INTO reservation (
                            admission_id, booking_attempt_id, sale_event_id, product_id, user_id,
                            status, hold_expires_at, released_reason
                        )
                        VALUES (?, ?, 1, 1, 99, 'RELEASED', DATE_SUB(NOW(6), INTERVAL 1 SECOND), 'TEST_RELEASED')
                        """,
                admissionId,
                bookingAttemptId
        );
        Long reservationId = jdbcTemplate.queryForObject(
                "SELECT id FROM reservation WHERE booking_attempt_id = ?",
                Long.class,
                bookingAttemptId
        );
        jdbcTemplate.update(
                """
                        INSERT INTO payment_attempt (
                            booking_attempt_id, reservation_id, status, method_type, amount,
                            provider_order_id, provider_payment_id, first_unknown_at,
                            active_reconcile_until, next_reconcile_at
                        )
                        VALUES (
                            ?, ?, 'RECONCILING_AFTER_RELEASE', 'CREDIT_CARD', 10000,
                            ?, ?, NOW(6), DATE_ADD(NOW(6), INTERVAL 1 MINUTE),
                            DATE_SUB(NOW(6), INTERVAL 1 SECOND)
                        )
                        """,
                bookingAttemptId,
                reservationId,
                bookingAttemptId,
                "mock_pg_" + bookingAttemptId
        );
        jdbcTemplate.update(
                """
                        INSERT INTO mock_pg_payment (
                            provider_order_id, provider_payment_id, scenario, status, confirm_count
                        )
                        VALUES (?, ?, 'LATE_SUCCESS', ?, 1)
                        """,
                bookingAttemptId,
                "mock_pg_" + bookingAttemptId,
                pgStatus
        );
    }

    private void seedAdmittedAttemptWithoutReservation(String bookingAttemptId, long userId) {
        jdbcTemplate.update(
                """
                        INSERT INTO admission_sequence (sale_event_id, product_id, next_seq, gate_mode)
                        VALUES (1, 1, 1, 'REDIS')
                        """
        );
        jdbcTemplate.update(
                """
                        INSERT INTO booking_admission (
                            sale_event_id, product_id, user_id, gate_mode, db_admission_seq,
                            candidate_rank, status, booking_attempt_id, admitted_at
                        )
                        VALUES (1, 1, ?, 'REDIS', 1, 1, 'ADMITTED', ?, NOW(6))
                        """,
                userId,
                bookingAttemptId
        );
    }

    private void seedHeldAttempt(String bookingAttemptId, LocalDateTime holdExpiresAt, String paymentStatus) {
        jdbcTemplate.update(
                """
                        INSERT INTO admission_sequence (sale_event_id, product_id, next_seq, gate_mode)
                        VALUES (1, 1, 1, 'REDIS')
                        """
        );
        jdbcTemplate.update(
                """
                        INSERT INTO booking_admission (
                            sale_event_id, product_id, user_id, gate_mode, db_admission_seq,
                            candidate_rank, status, booking_attempt_id, admitted_at
                        )
                        VALUES (1, 1, 99, 'REDIS', 1, 1, 'ADMITTED', ?, NOW(6))
                        """,
                bookingAttemptId
        );
        Long admissionId = jdbcTemplate.queryForObject(
                "SELECT id FROM booking_admission WHERE booking_attempt_id = ?",
                Long.class,
                bookingAttemptId
        );
        jdbcTemplate.update(
                "UPDATE sale_inventory SET reserved_count = reserved_count + 1 WHERE sale_event_id = 1 AND product_id = 1"
        );
        jdbcTemplate.update(
                """
                        INSERT INTO reservation (
                            admission_id, booking_attempt_id, sale_event_id, product_id, user_id,
                            status, hold_expires_at
                        )
                        VALUES (?, ?, 1, 1, 99, 'HELD', ?)
                        """,
                admissionId,
                bookingAttemptId,
                holdExpiresAt
        );
        Long reservationId = jdbcTemplate.queryForObject(
                "SELECT id FROM reservation WHERE booking_attempt_id = ?",
                Long.class,
                bookingAttemptId
        );
        jdbcTemplate.update(
                """
                        INSERT INTO payment_attempt (
                            booking_attempt_id, reservation_id, status, method_type, amount,
                            provider_order_id, confirm_started_at, next_reconcile_at
                        )
                        VALUES (?, ?, ?, 'CREDIT_CARD', 10000, ?, NOW(6), DATE_SUB(NOW(6), INTERVAL 1 SECOND))
                        """,
                bookingAttemptId,
                reservationId,
                paymentStatus,
                bookingAttemptId
        );
    }

    private BookingCommand command(long userId) {
        String token = attemptTokenService.issue(userId, 1, 1).rawToken();
        return commandWithPlan(userId, token, List.of(
                new PaymentPlanLine(PaymentMethodType.CREDIT_CARD, 10000)
        ), MockPgScenario.SUCCESS);
    }

    private void assertOccupiedCountEventuallyZero() throws InterruptedException {
        AssertionError lastFailure = null;
        for (int i = 0; i < 20; i++) {
            InventorySnapshot inventory = repository.inventory(1, 1);
            try {
                assertThat(inventory.occupiedCount()).isZero();
                return;
            } catch (AssertionError failure) {
                lastFailure = failure;
                Thread.sleep(10);
            }
        }
        throw lastFailure == null ? new AssertionError("Inventory was not observed") : lastFailure;
    }

    private BookingCommand commandWithScenario(long userId, MockPgScenario scenario) {
        String token = attemptTokenService.issue(userId, 1, 1).rawToken();
        return commandWithPlan(userId, token, List.of(
                new PaymentPlanLine(PaymentMethodType.CREDIT_CARD, 10000)
        ), scenario);
    }

    private BookingCommand commandWithPlan(
            long userId,
            String token,
            List<PaymentPlanLine> lines,
            MockPgScenario scenario
    ) {
        return new BookingCommand(
                userId,
                1,
                1,
                token,
                PaymentPlan.from(lines),
                10000,
                "KRW",
                "v1",
                scenario
        );
    }
}
