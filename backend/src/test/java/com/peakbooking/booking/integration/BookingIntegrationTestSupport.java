package com.peakbooking.booking.integration;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
abstract class BookingIntegrationTestSupport {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("peak_booking")
            .withUsername("peak")
            .withPassword("peak");

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine")
            .withExposedPorts(6379);

    @Autowired
    protected JdbcTemplate jdbcTemplate;

    @Autowired
    protected StringRedisTemplate redisTemplate;

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.datasource.driver-class-name", MYSQL::getDriverClassName);
        registry.add("spring.sql.init.mode", () -> "always");
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        registry.add("peak-booking.recovery.enabled", () -> "false");
        registry.add("peak-booking.product-cache-ttl", () -> "0ms");
        registry.add("peak-booking.gate-mode-cache-ttl", () -> "0ms");
        registry.add("peak-booking.hold-timeout", () -> "100ms");
        registry.add("peak-booking.waiting-timeout", () -> "300ms");
        registry.add("peak-booking.reconciliation-window", () -> "300ms");
        registry.add("peak-booking.payment.call-timeout", () -> "50ms");
        registry.add("peak-booking.payment.confirm-recovery-grace", () -> "20ms");
        registry.add("peak-booking.payment.mock-normal-delay", () -> "1ms");
        registry.add("peak-booking.payment.mock-timeout-delay", () -> "80ms");
    }

    @BeforeEach
    void resetDatabase() {
        redisTemplate.getConnectionFactory().getConnection().serverCommands().flushAll();
        jdbcTemplate.update("DELETE FROM mock_pg_payment");
        jdbcTemplate.update("DELETE FROM point_hold");
        jdbcTemplate.update("DELETE FROM payment_attempt");
        jdbcTemplate.update("DELETE FROM idempotency_record");
        jdbcTemplate.update("DELETE FROM reservation");
        jdbcTemplate.update("DELETE FROM booking_admission");
        jdbcTemplate.update("DELETE FROM admission_sequence");
        jdbcTemplate.update("DELETE FROM sale_inventory");
        jdbcTemplate.update("DELETE FROM point_account");
        jdbcTemplate.update("DELETE FROM product");
    }

    protected void seedProductAndInventory(int totalStock) {
        jdbcTemplate.update(
                """
                        INSERT INTO product (
                            id, name, price_amount, currency, sale_open_at, check_in_at, check_out_at
                        )
                        VALUES (1, 'Peak Room', 10000, 'KRW', NOW(6), NOW(6), DATE_ADD(NOW(6), INTERVAL 1 DAY))
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

    protected void seedPoints(long userId, long amount) {
        jdbcTemplate.update(
                "INSERT INTO point_account (user_id, available_points, held_points) VALUES (?, ?, 0)",
                userId,
                amount
        );
    }
}
