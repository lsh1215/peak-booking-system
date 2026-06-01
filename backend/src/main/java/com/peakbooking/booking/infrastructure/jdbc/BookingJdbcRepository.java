package com.peakbooking.booking.infrastructure.jdbc;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.peakbooking.booking.application.BookingResult;
import com.peakbooking.booking.application.ProductSummary;
import com.peakbooking.booking.domain.AdmissionDecision;
import com.peakbooking.booking.domain.AdmissionStatus;
import com.peakbooking.booking.domain.GateMode;
import com.peakbooking.booking.domain.IdempotencyStatus;
import com.peakbooking.booking.domain.PaymentAttemptStatus;
import com.peakbooking.booking.domain.PointHoldStatus;
import com.peakbooking.booking.domain.ReservationStatus;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class BookingJdbcRepository {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public BookingJdbcRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public Optional<ProductSummary> findProduct(long productId) {
        try {
            return Optional.ofNullable(jdbcTemplate.queryForObject(
                    """
                            SELECT id, name, price_amount, currency, sale_open_at, check_in_at, check_out_at
                            FROM product
                            WHERE id = ?
                            """,
                    this::mapProduct,
                    productId
            ));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    public long availablePoints(long userId) {
        Long points = jdbcTemplate.queryForObject(
                "SELECT COALESCE(MAX(available_points), 0) FROM point_account WHERE user_id = ?",
                Long.class,
                userId
        );
        return points == null ? 0 : points;
    }

    public void ensureAdmissionSequence(long saleEventId, long productId) {
        jdbcTemplate.update(
                """
                        INSERT INTO admission_sequence (sale_event_id, product_id, next_seq, gate_mode)
                        VALUES (?, ?, 0, 'REDIS')
                        ON DUPLICATE KEY UPDATE id = id
                        """,
                saleEventId,
                productId
        );
    }

    public GateMode gateMode(long saleEventId, long productId) {
        try {
            String value = jdbcTemplate.queryForObject(
                    "SELECT gate_mode FROM admission_sequence WHERE sale_event_id = ? AND product_id = ?",
                    String.class,
                    saleEventId,
                    productId
            );
            return GateMode.valueOf(value);
        } catch (EmptyResultDataAccessException e) {
            return GateMode.REDIS;
        }
    }

    public void markDbFallback(long saleEventId, long productId) {
        ensureAdmissionSequence(saleEventId, productId);
        jdbcTemplate.update(
                """
                        UPDATE admission_sequence
                        SET gate_mode = 'DB_FALLBACK'
                        WHERE sale_event_id = ? AND product_id = ?
                        """,
                saleEventId,
                productId
        );
    }

    public AdmissionDecision createAdmission(
            long saleEventId,
            long productId,
            long userId,
            GateMode gateMode,
            Long redisSeq,
            int candidateLimit,
            LocalDateTime now
    ) {
        Optional<AdmissionDecision> existing = findAdmission(saleEventId, productId, userId);
        if (existing.isPresent()) {
            return existing.get();
        }

        ensureAdmissionSequence(saleEventId, productId);
        int updated = jdbcTemplate.update(
                """
                        UPDATE admission_sequence
                        SET next_seq = next_seq + 1
                        WHERE sale_event_id = ? AND product_id = ? AND next_seq < ?
                        """,
                saleEventId,
                productId,
                candidateLimit
        );
        if (updated != 1) {
            return AdmissionDecision.rejected(gateMode);
        }
        Long dbSeq = jdbcTemplate.queryForObject(
                """
                        SELECT next_seq
                        FROM admission_sequence
                        WHERE sale_event_id = ? AND product_id = ?
                        """,
                Long.class,
                saleEventId,
                productId
        );
        if (dbSeq == null || dbSeq <= 0 || dbSeq > candidateLimit) {
            return AdmissionDecision.rejected(gateMode);
        }

        try {
            jdbcTemplate.update(
                    """
                            INSERT INTO booking_admission (
                                sale_event_id, product_id, user_id, gate_mode, redis_seq,
                                db_admission_seq, candidate_rank, status, admitted_at
                            )
                            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                            """,
                    saleEventId,
                    productId,
                    userId,
                    gateMode.name(),
                    redisSeq,
                    dbSeq,
                    dbSeq.intValue(),
                    AdmissionStatus.ADMITTED.name(),
                    now
            );
        } catch (DuplicateKeyException e) {
            return findAdmission(saleEventId, productId, userId)
                    .orElseThrow(() -> e);
        }
        Long admissionId = jdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        return AdmissionDecision.admitted(
                admissionId == null ? 0 : admissionId,
                dbSeq,
                redisSeq,
                dbSeq.intValue(),
                gateMode
        );
    }

    public Optional<AdmissionDecision> findAdmission(long saleEventId, long productId, long userId) {
        try {
            return Optional.ofNullable(jdbcTemplate.queryForObject(
                    """
                            SELECT id, db_admission_seq, redis_seq, candidate_rank, gate_mode
                            FROM booking_admission
                            WHERE sale_event_id = ? AND product_id = ? AND user_id = ?
                            """,
                    (rs, rowNum) -> AdmissionDecision.admitted(
                            rs.getLong("id"),
                            rs.getLong("db_admission_seq"),
                            nullableLong(rs, "redis_seq"),
                            rs.getInt("candidate_rank"),
                            GateMode.valueOf(rs.getString("gate_mode"))
                    ),
                    saleEventId,
                    productId,
                    userId
            ));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    public Optional<AdmissionRecord> findAdmissionRecord(long admissionId) {
        try {
            return Optional.ofNullable(jdbcTemplate.queryForObject(
                    """
                            SELECT id, sale_event_id, product_id, user_id, db_admission_seq, status, waiting_expires_at
                            FROM booking_admission
                            WHERE id = ?
                            """,
                    this::mapAdmissionRecord,
                    admissionId
            ));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    public Optional<AdmissionRecord> findAdmissionByAttempt(String bookingAttemptId) {
        try {
            return Optional.ofNullable(jdbcTemplate.queryForObject(
                    """
                            SELECT id, sale_event_id, product_id, user_id, db_admission_seq, status, waiting_expires_at
                            FROM booking_admission
                            WHERE booking_attempt_id = ?
                            """,
                    this::mapAdmissionRecord,
                    bookingAttemptId
            ));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    public Optional<Long> createHeldReservation(
            long admissionId,
            String bookingAttemptId,
            long saleEventId,
            long productId,
            long userId,
            LocalDateTime holdExpiresAt
    ) {
        int updated = jdbcTemplate.update(
                """
                        UPDATE sale_inventory
                        SET reserved_count = reserved_count + 1
                        WHERE sale_event_id = ?
                          AND product_id = ?
                          AND reserved_count + payment_unknown_count + confirmed_count < total_count
                        """,
                saleEventId,
                productId
        );
        if (updated != 1) {
            return Optional.empty();
        }
        try {
            jdbcTemplate.update(
                    """
                            INSERT INTO reservation (
                                admission_id, booking_attempt_id, sale_event_id, product_id, user_id,
                                status, hold_expires_at
                            )
                            VALUES (?, ?, ?, ?, ?, ?, ?)
                            """,
                    admissionId,
                    bookingAttemptId,
                    saleEventId,
                    productId,
                    userId,
                    ReservationStatus.HELD.name(),
                    holdExpiresAt
            );
        } catch (DuplicateKeyException e) {
            decrementReserved(saleEventId, productId);
            return findReservationByAttempt(bookingAttemptId).map(ReservationRecord::id);
        }
        Long id = jdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        return Optional.of(id == null ? 0 : id);
    }

    public Optional<ReservationRecord> findReservationByAttempt(String bookingAttemptId) {
        try {
            return Optional.ofNullable(jdbcTemplate.queryForObject(
                    "SELECT * FROM reservation WHERE booking_attempt_id = ?",
                    reservationMapper(),
                    bookingAttemptId
            ));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    public void markAdmissionWaiting(long admissionId, LocalDateTime waitingExpiresAt) {
        jdbcTemplate.update(
                """
                        UPDATE booking_admission
                        SET status = ?,
                            waiting_expires_at = COALESCE(waiting_expires_at, ?)
                        WHERE id = ?
                        """,
                AdmissionStatus.WAITING_CANDIDATE.name(),
                waitingExpiresAt,
                admissionId
        );
    }

    public void attachAttemptToAdmission(long admissionId, String bookingAttemptId) {
        jdbcTemplate.update(
                """
                        UPDATE booking_admission
                        SET booking_attempt_id = COALESCE(booking_attempt_id, ?)
                        WHERE id = ?
                        """,
                bookingAttemptId,
                admissionId
        );
    }

    public boolean isEarliestWaitingCandidate(long admissionId, LocalDateTime now) {
        Integer blockers = jdbcTemplate.queryForObject(
                """
                        SELECT COUNT(*)
                        FROM booking_admission target
                        JOIN booking_admission other
                          ON other.sale_event_id = target.sale_event_id
                         AND other.product_id = target.product_id
                        WHERE target.id = ?
                          AND other.db_admission_seq < target.db_admission_seq
                          AND other.status = 'WAITING_CANDIDATE'
                          AND other.waiting_expires_at > ?
                        """,
                Integer.class,
                admissionId,
                now
        );
        return blockers == null || blockers == 0;
    }

    public void markAdmissionWaitingExpired(long admissionId, LocalDateTime now) {
        jdbcTemplate.update(
                """
                        UPDATE booking_admission
                        SET status = ?, completed_at = ?
                        WHERE id = ? AND status = 'WAITING_CANDIDATE'
                        """,
                AdmissionStatus.WAITING_EXPIRED.name(),
                now,
                admissionId
        );
    }

    public Optional<ReservationRecord> findReservationForUpdate(long reservationId) {
        try {
            return Optional.ofNullable(jdbcTemplate.queryForObject(
                    "SELECT * FROM reservation WHERE id = ? FOR UPDATE",
                    reservationMapper(),
                    reservationId
            ));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    public boolean confirmReservation(ReservationRecord reservation, LocalDateTime now) {
        if (reservation.status() == ReservationStatus.PAYMENT_UNKNOWN
                && (reservation.unknownInventoryDeadlineAt() == null
                || !reservation.unknownInventoryDeadlineAt().isAfter(now))) {
            return false;
        }
        if (reservation.status() == ReservationStatus.HELD) {
            jdbcTemplate.update(
                    """
                            UPDATE sale_inventory
                            SET reserved_count = reserved_count - 1,
                                confirmed_count = confirmed_count + 1
                            WHERE sale_event_id = ? AND product_id = ?
                            """,
                    reservation.saleEventId(),
                    reservation.productId()
            );
        } else if (reservation.status() == ReservationStatus.PAYMENT_UNKNOWN) {
            jdbcTemplate.update(
                    """
                            UPDATE sale_inventory
                            SET payment_unknown_count = payment_unknown_count - 1,
                                confirmed_count = confirmed_count + 1
                            WHERE sale_event_id = ? AND product_id = ?
                            """,
                    reservation.saleEventId(),
                    reservation.productId()
            );
        }
        int updated = jdbcTemplate.update(
                """
                        UPDATE reservation
                        SET status = ?, confirmed_at = ?
                        WHERE id = ?
                          AND status IN ('HELD', 'PAYMENT_UNKNOWN')
                          AND (status = 'HELD' OR unknown_inventory_deadline_at > ?)
                        """,
                ReservationStatus.CONFIRMED.name(),
                now,
                reservation.id(),
                now
        );
        if (updated != 1) {
            return false;
        }
        jdbcTemplate.update(
                "UPDATE booking_admission SET status = ?, completed_at = ? WHERE id = ?",
                AdmissionStatus.SUCCEEDED.name(),
                now,
                reservation.admissionId()
        );
        return true;
    }

    public void releaseReservation(ReservationRecord reservation, String reason, LocalDateTime now) {
        if (reservation.status() == ReservationStatus.HELD) {
            decrementReserved(reservation.saleEventId(), reservation.productId());
        } else if (reservation.status() == ReservationStatus.PAYMENT_UNKNOWN) {
            jdbcTemplate.update(
                    """
                            UPDATE sale_inventory
                            SET payment_unknown_count = payment_unknown_count - 1
                            WHERE sale_event_id = ? AND product_id = ?
                            """,
                    reservation.saleEventId(),
                    reservation.productId()
            );
        }
        jdbcTemplate.update(
                """
                        UPDATE reservation
                        SET status = ?, released_reason = ?
                        WHERE id = ? AND status IN ('HELD', 'PAYMENT_UNKNOWN')
                        """,
                ReservationStatus.RELEASED.name(),
                reason,
                reservation.id()
        );
        jdbcTemplate.update(
                "UPDATE booking_admission SET status = ?, completed_at = ? WHERE id = ?",
                AdmissionStatus.FAILED.name(),
                now,
                reservation.admissionId()
        );
    }

    public void markPaymentUnknown(
            ReservationRecord reservation,
            LocalDateTime unknownDeadline,
            LocalDateTime firstUnknownAt,
            LocalDateTime activeReconcileUntil,
            String providerPaymentId
    ) {
        jdbcTemplate.update(
                """
                        UPDATE sale_inventory
                        SET reserved_count = reserved_count - 1,
                            payment_unknown_count = payment_unknown_count + 1
                        WHERE sale_event_id = ? AND product_id = ?
                        """,
                reservation.saleEventId(),
                reservation.productId()
        );
        jdbcTemplate.update(
                """
                        UPDATE reservation
                        SET status = ?, unknown_inventory_deadline_at = ?
                        WHERE id = ? AND status = 'HELD'
                        """,
                ReservationStatus.PAYMENT_UNKNOWN.name(),
                unknownDeadline,
                reservation.id()
        );
        jdbcTemplate.update(
                """
                        UPDATE payment_attempt
                        SET status = ?, provider_payment_id = ?, first_unknown_at = ?,
                            active_reconcile_until = ?, next_reconcile_at = ?
                        WHERE booking_attempt_id = ?
                        """,
                PaymentAttemptStatus.PAYMENT_UNKNOWN.name(),
                providerPaymentId,
                firstUnknownAt,
                activeReconcileUntil,
                firstUnknownAt,
                reservation.bookingAttemptId()
        );
    }

    public void createIdempotency(String bookingAttemptId, String requestHash, LocalDateTime expiresAt) {
        jdbcTemplate.update(
                """
                        INSERT INTO idempotency_record (booking_attempt_id, request_hash, status, expires_at)
                        VALUES (?, ?, ?, ?)
                        """,
                bookingAttemptId,
                requestHash,
                IdempotencyStatus.IN_PROGRESS.name(),
                expiresAt
        );
    }

    public Optional<IdempotencyRecord> findIdempotency(String bookingAttemptId) {
        try {
            return Optional.ofNullable(jdbcTemplate.queryForObject(
                    "SELECT * FROM idempotency_record WHERE booking_attempt_id = ?",
                    this::mapIdempotency,
                    bookingAttemptId
            ));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    public void completeIdempotency(String bookingAttemptId, BookingResult result) {
        jdbcTemplate.update(
                """
                        UPDATE idempotency_record
                        SET status = ?, http_status = ?, business_code = ?, response_snapshot = ?, reservation_id = ?
                        WHERE booking_attempt_id = ?
                        """,
                result.httpStatus() >= 400 ? IdempotencyStatus.FAILED.name() : IdempotencyStatus.COMPLETED.name(),
                result.httpStatus(),
                result.businessCode(),
                serialize(result),
                result.reservationId(),
                bookingAttemptId
        );
    }

    public void completeIdempotencyIfExists(String bookingAttemptId, BookingResult result) {
        jdbcTemplate.update(
                """
                        UPDATE idempotency_record
                        SET status = ?, http_status = ?, business_code = ?, response_snapshot = ?, reservation_id = ?
                        WHERE booking_attempt_id = ? AND http_status IS NULL
                        """,
                result.httpStatus() >= 400 ? IdempotencyStatus.FAILED.name() : IdempotencyStatus.COMPLETED.name(),
                result.httpStatus(),
                result.businessCode(),
                serialize(result),
                result.reservationId(),
                bookingAttemptId
        );
    }

    public void createPaymentAttempt(String bookingAttemptId, long reservationId, String methodType, long amount) {
        jdbcTemplate.update(
                """
                        INSERT INTO payment_attempt (booking_attempt_id, reservation_id, status, method_type, amount)
                        VALUES (?, ?, ?, ?, ?)
                        """,
                bookingAttemptId,
                reservationId,
                PaymentAttemptStatus.REQUESTED.name(),
                methodType,
                amount
        );
    }

    public Optional<PaymentAttemptRecord> findPaymentAttempt(String bookingAttemptId) {
        try {
            return Optional.ofNullable(jdbcTemplate.queryForObject(
                    "SELECT * FROM payment_attempt WHERE booking_attempt_id = ?",
                    this::mapPaymentAttempt,
                    bookingAttemptId
            ));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    public void markPaymentConfirmed(String bookingAttemptId, String providerPaymentId) {
        jdbcTemplate.update(
                "UPDATE payment_attempt SET status = ?, provider_payment_id = ? WHERE booking_attempt_id = ?",
                PaymentAttemptStatus.CONFIRMED.name(),
                providerPaymentId,
                bookingAttemptId
        );
    }

    public void markPaymentFailed(String bookingAttemptId, String errorCode) {
        jdbcTemplate.update(
                "UPDATE payment_attempt SET status = ?, last_error_code = ? WHERE booking_attempt_id = ?",
                PaymentAttemptStatus.FAILED.name(),
                errorCode,
                bookingAttemptId
        );
    }

    public void markReconcilingAfterRelease(String bookingAttemptId) {
        jdbcTemplate.update(
                "UPDATE payment_attempt SET status = ? WHERE booking_attempt_id = ?",
                PaymentAttemptStatus.RECONCILING_AFTER_RELEASE.name(),
                bookingAttemptId
        );
    }

    public void markLateSuccessCancelPending(String bookingAttemptId) {
        jdbcTemplate.update(
                "UPDATE payment_attempt SET status = ? WHERE booking_attempt_id = ?",
                PaymentAttemptStatus.LATE_SUCCESS_CANCEL_PENDING.name(),
                bookingAttemptId
        );
    }

    public void markCancelledAfterRelease(String bookingAttemptId) {
        jdbcTemplate.update(
                "UPDATE payment_attempt SET status = ? WHERE booking_attempt_id = ?",
                PaymentAttemptStatus.CANCELLED_AFTER_RELEASE.name(),
                bookingAttemptId
        );
    }

    public void markManualReviewAfterWindow(LocalDateTime now) {
        jdbcTemplate.update(
                """
                        UPDATE payment_attempt
                        SET status = ?, manual_review_reason = ?
                        WHERE status IN ('PAYMENT_UNKNOWN', 'RECONCILING_AFTER_RELEASE', 'LATE_SUCCESS_CANCEL_PENDING')
                          AND active_reconcile_until IS NOT NULL
                          AND active_reconcile_until <= ?
                        """,
                PaymentAttemptStatus.MANUAL_REVIEW_REQUIRED.name(),
                "active reconciliation window expired",
                now
        );
    }

    public boolean holdPoints(long userId, String bookingAttemptId, long pointAmount) {
        if (pointAmount == 0) {
            return true;
        }
        int updated = jdbcTemplate.update(
                """
                        UPDATE point_account
                        SET available_points = available_points - ?,
                            held_points = held_points + ?
                        WHERE user_id = ? AND available_points >= ?
                        """,
                pointAmount,
                pointAmount,
                userId,
                pointAmount
        );
        if (updated != 1) {
            return false;
        }
        Long accountId = jdbcTemplate.queryForObject(
                "SELECT id FROM point_account WHERE user_id = ?",
                Long.class,
                userId
        );
        try {
            jdbcTemplate.update(
                    """
                            INSERT INTO point_hold (booking_attempt_id, point_account_id, amount, status)
                            VALUES (?, ?, ?, ?)
                            """,
                    bookingAttemptId,
                    accountId,
                    pointAmount,
                    PointHoldStatus.HELD.name()
            );
        } catch (DuplicateKeyException ignored) {
            return true;
        }
        return true;
    }

    public void capturePoints(String bookingAttemptId) {
        Optional<PointHoldRow> row = findHeldPointHold(bookingAttemptId);
        row.ifPresent(hold -> {
            jdbcTemplate.update(
                    "UPDATE point_account SET held_points = held_points - ? WHERE id = ?",
                    hold.amount(),
                    hold.pointAccountId()
            );
            jdbcTemplate.update(
                    "UPDATE point_hold SET status = ? WHERE id = ?",
                    PointHoldStatus.CAPTURED.name(),
                    hold.id()
            );
        });
    }

    public void releasePoints(String bookingAttemptId) {
        Optional<PointHoldRow> row = findHeldPointHold(bookingAttemptId);
        row.ifPresent(hold -> {
            jdbcTemplate.update(
                    """
                            UPDATE point_account
                            SET available_points = available_points + ?,
                                held_points = held_points - ?
                            WHERE id = ?
                            """,
                    hold.amount(),
                    hold.amount(),
                    hold.pointAccountId()
            );
            jdbcTemplate.update(
                    "UPDATE point_hold SET status = ? WHERE id = ?",
                    PointHoldStatus.RELEASED.name(),
                    hold.id()
            );
        });
    }

    public InventorySnapshot inventory(long saleEventId, long productId) {
        return jdbcTemplate.queryForObject(
                """
                        SELECT total_count, reserved_count, payment_unknown_count, confirmed_count
                        FROM sale_inventory
                        WHERE sale_event_id = ? AND product_id = ?
                        """,
                (rs, rowNum) -> new InventorySnapshot(
                        rs.getInt("total_count"),
                        rs.getInt("reserved_count"),
                        rs.getInt("payment_unknown_count"),
                        rs.getInt("confirmed_count")
                ),
                saleEventId,
                productId
        );
    }

    public int countRows(String tableName) {
        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + tableName, Integer.class);
        return count == null ? 0 : count;
    }

    public List<ReservationRecord> staleHeldOrUnknown(LocalDateTime now, int limit) {
        return jdbcTemplate.query(
                """
                        SELECT r.*
                        FROM reservation r
                        LEFT JOIN payment_attempt p ON p.booking_attempt_id = r.booking_attempt_id
                        WHERE (
                            r.status = 'HELD' AND r.hold_expires_at <= ?
                        ) OR (
                            r.status = 'PAYMENT_UNKNOWN'
                            AND p.next_reconcile_at IS NOT NULL
                            AND p.next_reconcile_at <= ?
                        )
                        ORDER BY r.id
                        LIMIT ?
                        """,
                reservationMapper(),
                now,
                now,
                limit
        );
    }

    public List<RecoveryClaim> claimDueRecoveries(
            LocalDateTime now,
            String leaseOwner,
            String leaseToken,
            LocalDateTime leaseUntil,
            int limit
    ) {
        List<RecoveryClaim> claims = jdbcTemplate.query(
                """
                        SELECT r.*,
                               p.id AS p_id,
                               p.reservation_id AS p_reservation_id,
                               p.status AS p_status,
                               p.provider_payment_id AS p_provider_payment_id,
                               p.first_unknown_at AS p_first_unknown_at,
                               p.active_reconcile_until AS p_active_reconcile_until,
                               p.next_reconcile_at AS p_next_reconcile_at,
                               p.reconcile_attempt_count AS p_reconcile_attempt_count,
                               p.lease_token AS p_lease_token
                        FROM reservation r
                        JOIN payment_attempt p ON p.booking_attempt_id = r.booking_attempt_id
                        WHERE (
                            (r.status = 'HELD' AND r.hold_expires_at <= ?)
                            OR (
                                r.status = 'PAYMENT_UNKNOWN'
                                AND p.next_reconcile_at IS NOT NULL
                                AND p.next_reconcile_at <= ?
                            )
                        )
                        AND (p.lease_until IS NULL OR p.lease_until < ?)
                        ORDER BY r.id
                        LIMIT ?
                        FOR UPDATE SKIP LOCKED
                        """,
                (rs, rowNum) -> new RecoveryClaim(mapReservationFromCurrentRow(rs), mapPaymentAttemptWithPrefix(rs), leaseToken),
                now,
                now,
                now,
                limit
        );
        for (RecoveryClaim claim : claims) {
            jdbcTemplate.update(
                    """
                            UPDATE payment_attempt
                            SET lease_owner = ?, lease_token = ?, lease_until = ?
                            WHERE id = ?
                            """,
                    leaseOwner,
                    leaseToken,
                    leaseUntil,
                    claim.paymentAttempt().id()
            );
        }
        return claims;
    }

    public boolean leaseTokenMatches(String bookingAttemptId, String leaseToken) {
        Integer count = jdbcTemplate.queryForObject(
                """
                        SELECT COUNT(*)
                        FROM payment_attempt
                        WHERE booking_attempt_id = ? AND lease_token = ?
                        """,
                Integer.class,
                bookingAttemptId,
                leaseToken
        );
        return count != null && count == 1;
    }

    private void decrementReserved(long saleEventId, long productId) {
        jdbcTemplate.update(
                """
                        UPDATE sale_inventory
                        SET reserved_count = reserved_count - 1
                        WHERE sale_event_id = ? AND product_id = ?
                        """,
                saleEventId,
                productId
        );
    }

    private Optional<PointHoldRow> findHeldPointHold(String bookingAttemptId) {
        try {
            return Optional.ofNullable(jdbcTemplate.queryForObject(
                    """
                            SELECT id, point_account_id, amount
                            FROM point_hold
                            WHERE booking_attempt_id = ? AND status = 'HELD'
                            """,
                    (rs, rowNum) -> new PointHoldRow(
                            rs.getLong("id"),
                            rs.getLong("point_account_id"),
                            rs.getLong("amount")
                    ),
                    bookingAttemptId
            ));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    private ProductSummary mapProduct(ResultSet rs, int rowNum) throws SQLException {
        return new ProductSummary(
                rs.getLong("id"),
                rs.getString("name"),
                rs.getLong("price_amount"),
                rs.getString("currency"),
                rs.getTimestamp("sale_open_at").toLocalDateTime(),
                rs.getTimestamp("check_in_at").toLocalDateTime(),
                rs.getTimestamp("check_out_at").toLocalDateTime()
        );
    }

    private IdempotencyRecord mapIdempotency(ResultSet rs, int rowNum) throws SQLException {
        return new IdempotencyRecord(
                rs.getString("booking_attempt_id"),
                rs.getString("request_hash"),
                IdempotencyStatus.valueOf(rs.getString("status")),
                (Integer) rs.getObject("http_status"),
                rs.getString("business_code"),
                rs.getString("response_snapshot"),
                nullableLong(rs, "reservation_id")
        );
    }

    private AdmissionRecord mapAdmissionRecord(ResultSet rs, int rowNum) throws SQLException {
        return new AdmissionRecord(
                rs.getLong("id"),
                rs.getLong("sale_event_id"),
                rs.getLong("product_id"),
                rs.getLong("user_id"),
                rs.getLong("db_admission_seq"),
                AdmissionStatus.valueOf(rs.getString("status")),
                nullableDateTime(rs, "waiting_expires_at")
        );
    }

    private PaymentAttemptRecord mapPaymentAttempt(ResultSet rs, int rowNum) throws SQLException {
        return new PaymentAttemptRecord(
                rs.getLong("id"),
                rs.getString("booking_attempt_id"),
                nullableLong(rs, "reservation_id"),
                PaymentAttemptStatus.valueOf(rs.getString("status")),
                rs.getString("provider_payment_id"),
                nullableDateTime(rs, "first_unknown_at"),
                nullableDateTime(rs, "active_reconcile_until"),
                nullableDateTime(rs, "next_reconcile_at"),
                rs.getInt("reconcile_attempt_count"),
                rs.getString("lease_token")
        );
    }

    private RowMapper<ReservationRecord> reservationMapper() {
        return (rs, rowNum) -> new ReservationRecord(
                rs.getLong("id"),
                rs.getLong("admission_id"),
                rs.getString("booking_attempt_id"),
                rs.getLong("sale_event_id"),
                rs.getLong("product_id"),
                rs.getLong("user_id"),
                ReservationStatus.valueOf(rs.getString("status")),
                nullableDateTime(rs, "hold_expires_at"),
                nullableDateTime(rs, "unknown_inventory_deadline_at")
        );
    }

    private ReservationRecord mapReservationFromCurrentRow(ResultSet rs) throws SQLException {
        return new ReservationRecord(
                rs.getLong("id"),
                rs.getLong("admission_id"),
                rs.getString("booking_attempt_id"),
                rs.getLong("sale_event_id"),
                rs.getLong("product_id"),
                rs.getLong("user_id"),
                ReservationStatus.valueOf(rs.getString("status")),
                nullableDateTime(rs, "hold_expires_at"),
                nullableDateTime(rs, "unknown_inventory_deadline_at")
        );
    }

    private PaymentAttemptRecord mapPaymentAttemptWithPrefix(ResultSet rs) throws SQLException {
        return new PaymentAttemptRecord(
                rs.getLong("p_id"),
                rs.getString("booking_attempt_id"),
                nullableLong(rs, "p_reservation_id"),
                PaymentAttemptStatus.valueOf(rs.getString("p_status")),
                rs.getString("p_provider_payment_id"),
                nullableDateTime(rs, "p_first_unknown_at"),
                nullableDateTime(rs, "p_active_reconcile_until"),
                nullableDateTime(rs, "p_next_reconcile_at"),
                rs.getInt("p_reconcile_attempt_count"),
                rs.getString("p_lease_token")
        );
    }

    private String serialize(BookingResult result) {
        try {
            return objectMapper.writeValueAsString(result);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize booking result", e);
        }
    }

    private static Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private static LocalDateTime nullableDateTime(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toLocalDateTime();
    }

    private record PointHoldRow(long id, long pointAccountId, long amount) {
    }
}
