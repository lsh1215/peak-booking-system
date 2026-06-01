package com.peakbooking.booking.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.peakbooking.booking.application.AttemptTokenService;
import com.peakbooking.booking.application.BookingApplicationService;
import com.peakbooking.booking.application.BookingCommand;
import com.peakbooking.booking.application.BookingResult;
import com.peakbooking.booking.application.MockPgScenario;
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

    @Test
    void should_reject_outside_candidate_without_mysql_write_side_effects_in_normal_redis_path() {
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
        assertThat(second.businessCode()).isEqualTo("ADMISSION_REJECTED");
        assertThat(count("booking_admission")).isEqualTo(admissions);
        assertThat(count("idempotency_record")).isEqualTo(idempotencies);
        assertThat(count("reservation")).isEqualTo(reservations);
        assertThat(count("payment_attempt")).isEqualTo(payments);
    }

    private int count(String tableName) {
        Integer value = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + tableName, Integer.class);
        return value == null ? 0 : value;
    }

    private BookingCommand command(long userId) {
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
                MockPgScenario.SUCCESS
        );
    }
}
