package com.peakbooking.booking.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.peakbooking.booking.application.token.AttemptTokenService;
import com.peakbooking.booking.application.BookingApplicationService;
import com.peakbooking.booking.application.dto.BookingCommand;
import com.peakbooking.booking.application.dto.BookingResult;
import com.peakbooking.booking.payment.MockPgScenario;
import com.peakbooking.booking.application.recovery.RecoveryWorkerService;
import com.peakbooking.booking.domain.PaymentMethodType;
import com.peakbooking.booking.domain.PaymentPlan;
import com.peakbooking.booking.domain.PaymentPlanLine;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(properties = "peak-booking.candidate-limit=2")
@ActiveProfiles("test")
class RedisActiveWindowRecoveryIntegrationTest extends BookingIntegrationTestSupport {

    @Autowired
    private BookingApplicationService bookingApplicationService;

    @Autowired
    private AttemptTokenService attemptTokenService;

    @Autowired
    private RecoveryWorkerService recoveryWorkerService;

    @Test
    void should_promote_next_redis_waiter_when_active_waiting_candidate_expires() {
        seedProductAndInventory(1);
        seedPoints(1, 0);
        seedPoints(2, 0);
        seedPoints(3, 0);

        BookingCommand firstCommand = command(1, MockPgScenario.TIMEOUT);
        BookingCommand secondCommand = command(2, MockPgScenario.SUCCESS);
        BookingCommand thirdCommand = command(3, MockPgScenario.SUCCESS);

        BookingResult first = bookingApplicationService.book(firstCommand);
        BookingResult secondWaitingCandidate = bookingApplicationService.book(secondCommand);
        BookingResult thirdWaitingRoom = bookingApplicationService.book(thirdCommand);

        assertThat(first.businessCode()).isEqualTo("PAYMENT_UNKNOWN");
        assertThat(secondWaitingCandidate.businessCode()).isEqualTo("WAITING_CANDIDATE");
        assertThat(thirdWaitingRoom.businessCode()).isEqualTo("WAITING_ROOM");
        assertThat(count("booking_admission")).isEqualTo(2);

        jdbcTemplate.update("UPDATE payment_attempt SET next_reconcile_at = DATE_ADD(NOW(6), INTERVAL 5 SECOND)");
        jdbcTemplate.update("UPDATE reservation SET unknown_inventory_deadline_at = DATE_ADD(NOW(6), INTERVAL 5 SECOND)");
        jdbcTemplate.update("""
                UPDATE booking_admission
                SET waiting_expires_at = DATE_SUB(NOW(6), INTERVAL 1 SECOND)
                WHERE user_id = 2
                """);

        int recovered = recoveryWorkerService.recoverDueReservations();
        BookingResult promoted = bookingApplicationService.book(thirdCommand);

        assertThat(recovered).isEqualTo(1);
        assertThat(countByStatus("booking_admission", "WAITING_EXPIRED")).isEqualTo(1);
        assertThat(redisTemplate.opsForHash().get("admit:1:1:users", "2")).isNull();
        assertThat(promoted.businessCode()).isEqualTo("WAITING_CANDIDATE");
        assertThat(count("booking_admission")).isEqualTo(3);
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
