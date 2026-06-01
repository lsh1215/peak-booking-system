package com.peakbooking.booking.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.peakbooking.booking.application.AttemptTokenService;
import com.peakbooking.booking.application.BookingApplicationService;
import com.peakbooking.booking.application.BookingCommand;
import com.peakbooking.booking.application.BookingResult;
import com.peakbooking.booking.application.CheckoutApplicationService;
import com.peakbooking.booking.application.MockPgScenario;
import com.peakbooking.booking.domain.PaymentMethodType;
import com.peakbooking.booking.domain.PaymentPlan;
import com.peakbooking.booking.domain.PaymentPlanLine;
import com.peakbooking.booking.infrastructure.jdbc.BookingJdbcRepository;
import com.peakbooking.booking.infrastructure.jdbc.InventorySnapshot;
import com.peakbooking.booking.payment.MockPaymentProvider;
import com.peakbooking.common.exception.BusinessException;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(properties = "peak-booking.candidate-limit=30")
@ActiveProfiles("test")
class BookingFlowIntegrationTest extends BookingIntegrationTestSupport {

    @Autowired
    private BookingApplicationService bookingApplicationService;

    @Autowired
    private AttemptTokenService attemptTokenService;

    @Autowired
    private BookingJdbcRepository repository;

    @Autowired
    private MockPaymentProvider mockPaymentProvider;

    @Autowired
    private CheckoutApplicationService checkoutApplicationService;

    @Test
    void should_never_confirm_more_than_stock_when_concurrent_booking_requests() throws Exception {
        seedProductAndInventory(10);
        for (long userId = 1; userId <= 20; userId++) {
            seedPoints(userId, 0);
        }

        try (var executor = Executors.newFixedThreadPool(12)) {
            List<Callable<BookingResult>> tasks = java.util.stream.LongStream.rangeClosed(1, 20)
                    .mapToObj(userId -> (Callable<BookingResult>) () -> bookingApplicationService.book(command(userId)))
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
                    .isEqualTo(10);
            InventorySnapshot inventory = repository.inventory(1, 1);
            assertThat(inventory.confirmedCount()).isEqualTo(10);
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
        assertThat(second.nextAction()).isEqualTo("REPLAY");
        assertThat(mockPaymentProvider.confirmCount(attemptId)).isEqualTo(1);
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
    void should_keep_db_fallback_sticky_for_same_sale_event_even_when_redis_is_available() {
        seedProductAndInventory(10);
        seedPoints(1, 0);
        repository.markDbFallback(1, 1);

        BookingResult result = bookingApplicationService.book(command(1));

        assertThat(result.businessCode()).isEqualTo("BOOKING_CONFIRMED");
        String gateMode = jdbcTemplate.queryForObject(
                "SELECT gate_mode FROM booking_admission WHERE user_id = 1",
                String.class
        );
        assertThat(gateMode).isEqualTo("DB_FALLBACK");
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
        assertThat(replay.businessCode()).isEqualTo("BOOKING_IN_PROGRESS");
        assertThat(replay.reservationStatus()).isEqualTo("PAYMENT_UNKNOWN");
        assertThat(count("booking_admission")).isEqualTo(admissions);
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

        Thread.sleep(180);
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

    private BookingCommand command(long userId) {
        String token = attemptTokenService.issue(userId, 1, 1).rawToken();
        return commandWithPlan(userId, token, List.of(
                new PaymentPlanLine(PaymentMethodType.CREDIT_CARD, 10000)
        ), MockPgScenario.SUCCESS);
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
