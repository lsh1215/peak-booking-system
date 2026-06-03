package com.peakbooking.booking.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.peakbooking.booking.application.token.AttemptTokenService;
import com.peakbooking.booking.application.BookingApplicationService;
import com.peakbooking.booking.application.dto.BookingCommand;
import com.peakbooking.booking.application.dto.BookingResult;
import com.peakbooking.booking.payment.MockPgScenario;
import com.peakbooking.booking.domain.PaymentMethodType;
import com.peakbooking.booking.domain.PaymentPlan;
import com.peakbooking.booking.domain.PaymentPlanLine;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(properties = "peak-booking.candidate-limit=1")
@ActiveProfiles("test")
class RedisAdmissionLimitIntegrationTest extends BookingIntegrationTestSupport {

    @Autowired
    private BookingApplicationService bookingApplicationService;

    @Autowired
    private AttemptTokenService attemptTokenService;

    @Autowired
    private com.peakbooking.booking.application.recovery.RecoveryWorkerService recoveryWorkerService;

    @Test
    void should_wait_outside_active_candidate_window_without_mysql_write_side_effects_in_normal_redis_path() {
        seedProductAndInventory(10);
        seedPoints(1, 0);
        seedPoints(2, 0);

        BookingResult first = bookingApplicationService.book(command(1));
        int admissions = count("booking_admission");
        int idempotencies = count("idempotency_record");
        int reservations = count("reservation");
        int payments = count("payment_attempt");

        BookingResult second = bookingApplicationService.book(command(2));

        assertThat(first.businessCode()).isEqualTo("BOOKING_CONFIRMED");
        assertThat(second.httpStatus()).isEqualTo(202);
        assertThat(second.businessCode()).isEqualTo("WAITING_ROOM");
        assertThat(second.retryable()).isTrue();
        assertThat(second.nextAction()).isEqualTo("RETRY_POST_BOOKINGS");
        assertThat(count("booking_admission")).isEqualTo(admissions);
        assertThat(count("idempotency_record")).isEqualTo(idempotencies);
        assertThat(count("reservation")).isEqualTo(reservations);
        assertThat(count("payment_attempt")).isEqualTo(payments);
    }

    @Test
    void should_promote_next_waiting_room_user_after_active_candidate_payment_failure() {
        seedProductAndInventory(10);
        seedPoints(1, 0);
        seedPoints(2, 0);

        BookingResult failed = bookingApplicationService.book(command(1, MockPgScenario.FAILURE));
        BookingResult promoted = bookingApplicationService.book(command(2, MockPgScenario.SUCCESS));

        assertThat(failed.businessCode()).isEqualTo("PAYMENT_FAILED");
        assertThat(promoted.businessCode()).isEqualTo("BOOKING_CONFIRMED");
        assertThat(count("booking_admission")).isEqualTo(2);
        assertThat(count("reservation")).isEqualTo(2);
        assertThat(count("payment_attempt")).isEqualTo(2);
    }

    @Test
    void should_promote_waiting_room_user_after_recovery_releases_unknown_active_candidate() throws Exception {
        seedProductAndInventory(1);
        seedPoints(1, 0);
        seedPoints(2, 0);

        BookingCommand firstCommand = command(1, MockPgScenario.TIMEOUT);
        BookingCommand secondCommand = command(2, MockPgScenario.SUCCESS);

        BookingResult first = bookingApplicationService.book(firstCommand);
        BookingResult secondWaiting = bookingApplicationService.book(secondCommand);

        assertThat(first.businessCode()).isEqualTo("PAYMENT_UNKNOWN");
        assertThat(secondWaiting.businessCode()).isEqualTo("WAITING_ROOM");
        assertThat(count("booking_admission")).isEqualTo(1);

        Thread.sleep(120);
        recoveryWorkerService.recoverDueReservations();
        BookingResult promoted = bookingApplicationService.book(secondCommand);

        assertThat(promoted.businessCode()).isEqualTo("BOOKING_CONFIRMED");
        assertThat(count("booking_admission")).isEqualTo(2);
    }

    @Test
    void should_promote_waiting_room_user_after_recovery_observes_pg_failure_before_unknown_deadline()
            throws InterruptedException {
        seedProductAndInventory(1);
        seedPoints(1, 0);
        seedPoints(2, 0);

        BookingCommand firstCommand = command(1, MockPgScenario.TIMEOUT);
        BookingCommand secondCommand = command(2, MockPgScenario.SUCCESS);

        BookingResult first = bookingApplicationService.book(firstCommand);
        BookingResult secondWaiting = bookingApplicationService.book(secondCommand);

        assertThat(first.businessCode()).isEqualTo("PAYMENT_UNKNOWN");
        assertThat(secondWaiting.businessCode()).isEqualTo("WAITING_ROOM");
        awaitRows("mock_pg_payment", 1);
        jdbcTemplate.update("""
                UPDATE mock_pg_payment
                SET scenario = 'FAILURE',
                    status = 'FAILED',
                    last_error_code = 'MOCK_PAYMENT_FAILED'
                """);
        assertThat(countByStatus("mock_pg_payment", "FAILED")).isEqualTo(1);
        jdbcTemplate.update("UPDATE payment_attempt SET next_reconcile_at = DATE_SUB(NOW(6), INTERVAL 1 SECOND)");
        jdbcTemplate.update("UPDATE reservation SET unknown_inventory_deadline_at = DATE_ADD(NOW(6), INTERVAL 5 SECOND)");

        int recovered = recoveryWorkerService.recoverDueReservations();
        assertThat(recovered).isEqualTo(1);
        assertThat(countByStatus("reservation", "RELEASED")).isEqualTo(1);
        assertThat(redisTemplate.opsForHash().get("admit:1:1:users", "1")).isNull();
        BookingResult promoted = bookingApplicationService.book(secondCommand);

        assertThat(promoted.businessCode()).isEqualTo("BOOKING_CONFIRMED");
        assertThat(count("booking_admission")).isEqualTo(2);
    }

    private void awaitRows(String tableName, int expectedRows) throws InterruptedException {
        long deadline = System.nanoTime() + 500_000_000L;
        while (System.nanoTime() < deadline) {
            if (count(tableName) == expectedRows) {
                return;
            }
            Thread.sleep(10);
        }
        assertThat(count(tableName)).isEqualTo(expectedRows);
    }

    private int count(String tableName) {
        Integer value = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + tableName, Integer.class);
        return value == null ? 0 : value;
    }

    private int countByStatus(String tableName, String status) {
        Integer value = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM " + tableName + " WHERE status = ?",
                Integer.class,
                status
        );
        return value == null ? 0 : value;
    }

    private BookingCommand command(long userId) {
        return command(userId, MockPgScenario.SUCCESS);
    }

    private BookingCommand command(long userId, MockPgScenario scenario) {
        String token = attemptTokenService.issue(userId, 1, 1).rawToken();
        return new BookingCommand(
                userId,
                1,
                1,
                token,
                PaymentPlan.from(List.of(new PaymentPlanLine(PaymentMethodType.CREDIT_CARD, 10000))),
                10000,
                "KRW",
                "v1",
                scenario
        );
    }
}
