package com.peakbooking.booking.infrastructure.jpa;

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
import com.peakbooking.booking.infrastructure.persistence.AdmissionRecord;
import com.peakbooking.booking.infrastructure.persistence.IdempotencyRecord;
import com.peakbooking.booking.infrastructure.persistence.InventorySnapshot;
import com.peakbooking.booking.infrastructure.persistence.PaymentAttemptRecord;
import com.peakbooking.booking.infrastructure.persistence.RecoveryClaim;
import com.peakbooking.booking.infrastructure.persistence.ReservationCreationResult;
import com.peakbooking.booking.infrastructure.persistence.ReservationRecord;
import com.peakbooking.booking.infrastructure.jpa.entity.AdmissionSequenceEntity;
import com.peakbooking.booking.infrastructure.jpa.entity.BookingAdmissionEntity;
import com.peakbooking.booking.infrastructure.jpa.entity.IdempotencyRecordEntity;
import com.peakbooking.booking.infrastructure.jpa.entity.PaymentAttemptEntity;
import com.peakbooking.booking.infrastructure.jpa.entity.PointHoldEntity;
import com.peakbooking.booking.infrastructure.jpa.entity.ReservationEntity;
import com.peakbooking.booking.infrastructure.jpa.repository.AdmissionSequenceJpaRepository;
import com.peakbooking.booking.infrastructure.jpa.repository.BookingAdmissionJpaRepository;
import com.peakbooking.booking.infrastructure.jpa.repository.IdempotencyRecordJpaRepository;
import com.peakbooking.booking.infrastructure.jpa.repository.PaymentAttemptJpaRepository;
import com.peakbooking.booking.infrastructure.jpa.repository.PointAccountJpaRepository;
import com.peakbooking.booking.infrastructure.jpa.repository.PointHoldJpaRepository;
import com.peakbooking.booking.infrastructure.jpa.repository.ProductJpaRepository;
import com.peakbooking.booking.infrastructure.jpa.repository.ReservationJpaRepository;
import com.peakbooking.booking.infrastructure.jpa.repository.SaleInventoryJpaRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Repository;

@Repository
public class BookingJpaRepository {

    private final ProductJpaRepository productRepository;
    private final SaleInventoryJpaRepository inventoryRepository;
    private final AdmissionSequenceJpaRepository admissionSequenceRepository;
    private final BookingAdmissionJpaRepository admissionRepository;
    private final ReservationJpaRepository reservationRepository;
    private final IdempotencyRecordJpaRepository idempotencyRepository;
    private final PaymentAttemptJpaRepository paymentAttemptRepository;
    private final PointAccountJpaRepository pointAccountRepository;
    private final PointHoldJpaRepository pointHoldRepository;
    private final ObjectMapper objectMapper;

    @PersistenceContext
    private EntityManager entityManager;

    public BookingJpaRepository(
            ProductJpaRepository productRepository,
            SaleInventoryJpaRepository inventoryRepository,
            AdmissionSequenceJpaRepository admissionSequenceRepository,
            BookingAdmissionJpaRepository admissionRepository,
            ReservationJpaRepository reservationRepository,
            IdempotencyRecordJpaRepository idempotencyRepository,
            PaymentAttemptJpaRepository paymentAttemptRepository,
            PointAccountJpaRepository pointAccountRepository,
            PointHoldJpaRepository pointHoldRepository,
            ObjectMapper objectMapper
    ) {
        this.productRepository = productRepository;
        this.inventoryRepository = inventoryRepository;
        this.admissionSequenceRepository = admissionSequenceRepository;
        this.admissionRepository = admissionRepository;
        this.reservationRepository = reservationRepository;
        this.idempotencyRepository = idempotencyRepository;
        this.paymentAttemptRepository = paymentAttemptRepository;
        this.pointAccountRepository = pointAccountRepository;
        this.pointHoldRepository = pointHoldRepository;
        this.objectMapper = objectMapper;
    }

    public Optional<ProductSummary> findProduct(long productId) {
        return productRepository.findById(productId)
                .map(product -> new ProductSummary(
                        product.getId(),
                        product.getName(),
                        product.getPriceAmount(),
                        product.getCurrency(),
                        product.getSaleOpenAt(),
                        product.getCheckInAt(),
                        product.getCheckOutAt()
                ));
    }

    public long availablePoints(long userId) {
        return pointAccountRepository.availablePoints(userId);
    }

    public void ensureAdmissionSequence(long saleEventId, long productId) {
        admissionSequenceRepository.ensure(saleEventId, productId);
    }

    public GateMode gateMode(long saleEventId, long productId) {
        return admissionSequenceRepository.findBySaleEventIdAndProductId(saleEventId, productId)
                .map(sequence -> sequence.getGateMode())
                .orElse(GateMode.REDIS);
    }

    public void markRedisFailoverPaused(long saleEventId, long productId) {
        ensureAdmissionSequence(saleEventId, productId);
        admissionSequenceRepository.markRedisFailoverPaused(saleEventId, productId);
    }

    public void markRedisRecovered(long saleEventId, long productId) {
        ensureAdmissionSequence(saleEventId, productId);
        admissionSequenceRepository.markRedisRecovered(saleEventId, productId);
    }

    public AdmissionDecision createAdmission(
            long saleEventId,
            long productId,
            long userId,
            String bookingAttemptId,
            GateMode gateMode,
            Long redisSeq,
            int candidateLimit,
            LocalDateTime now
    ) {
        ensureAdmissionSequence(saleEventId, productId);
        AdmissionSequenceEntity sequence = admissionSequenceRepository
                .findForUpdate(saleEventId, productId)
                .orElseThrow();

        if (sequence.getGateMode() != gateMode) {
            return AdmissionDecision.rejected(sequence.getGateMode());
        }
        Optional<AdmissionDecision> existing = findAdmission(saleEventId, productId, userId);
        if (existing.isPresent()) {
            return existing.get();
        }

        int updated = admissionSequenceRepository.incrementIfUnderLimit(saleEventId, productId, candidateLimit);
        if (updated != 1) {
            return AdmissionDecision.rejected(gateMode);
        }
        Long dbSeq = admissionSequenceRepository.nextSeq(saleEventId, productId);
        if (dbSeq == null || dbSeq <= 0 || dbSeq > candidateLimit) {
            return AdmissionDecision.rejected(gateMode);
        }

        try {
            BookingAdmissionEntity admission = BookingAdmissionEntity.admitted(
                    saleEventId,
                    productId,
                    userId,
                    gateMode,
                    redisSeq,
                    dbSeq,
                    dbSeq.intValue(),
                    bookingAttemptId,
                    now
            );
            BookingAdmissionEntity saved = admissionRepository.saveAndFlush(admission);
            return AdmissionDecision.admitted(saved.getId(), dbSeq, redisSeq, dbSeq.intValue(), gateMode);
        } catch (DataIntegrityViolationException e) {
            return findAdmission(saleEventId, productId, userId)
                    .orElseThrow(() -> e);
        }
    }

    public Optional<AdmissionDecision> findAdmission(long saleEventId, long productId, long userId) {
        return admissionRepository.findBySaleEventIdAndProductIdAndUserId(saleEventId, productId, userId)
                .filter(admission -> admission.getDbAdmissionSeq() != null)
                .map(this::toAdmissionDecision);
    }

    public Optional<AdmissionRecord> findAdmissionRecord(long admissionId) {
        return admissionRepository.findById(admissionId).map(this::toAdmissionRecord);
    }

    public Optional<AdmissionRecord> findAdmissionRecord(long saleEventId, long productId, long userId) {
        return admissionRepository.findBySaleEventIdAndProductIdAndUserId(saleEventId, productId, userId)
                .map(this::toAdmissionRecord);
    }

    public Optional<AdmissionRecord> findAdmissionByAttempt(String bookingAttemptId) {
        return admissionRepository.findByBookingAttemptId(bookingAttemptId).map(this::toAdmissionRecord);
    }

    public Optional<ReservationCreationResult> createHeldReservation(
            long admissionId,
            String bookingAttemptId,
            long saleEventId,
            long productId,
            long userId,
            LocalDateTime holdExpiresAt
    ) {
        int updated = inventoryRepository.incrementReservedIfAvailable(saleEventId, productId);
        if (updated != 1) {
            return Optional.empty();
        }
        try {
            ReservationEntity saved = reservationRepository.saveAndFlush(ReservationEntity.held(
                    admissionId,
                    bookingAttemptId,
                    saleEventId,
                    productId,
                    userId,
                    holdExpiresAt
            ));
            return Optional.of(new ReservationCreationResult(saved.getId(), true));
        } catch (DataIntegrityViolationException e) {
            decrementReserved(saleEventId, productId);
            return findReservationByAttempt(bookingAttemptId)
                    .map(reservation -> new ReservationCreationResult(reservation.id(), false));
        }
    }

    public Optional<ReservationRecord> findReservationByAttempt(String bookingAttemptId) {
        return reservationRepository.findByBookingAttemptId(bookingAttemptId).map(this::toReservationRecord);
    }

    public void markAdmissionWaiting(long admissionId, LocalDateTime waitingExpiresAt) {
        admissionRepository.markWaiting(admissionId, AdmissionStatus.WAITING_CANDIDATE, waitingExpiresAt);
    }

    public void attachAttemptToAdmission(long admissionId, String bookingAttemptId) {
        admissionRepository.attachAttempt(admissionId, bookingAttemptId);
    }

    public boolean isEarliestWaitingCandidate(long admissionId, LocalDateTime now) {
        return admissionRepository.countEarlierWaitingCandidates(admissionId, now) == 0;
    }

    public void markAdmissionWaitingExpired(long admissionId, LocalDateTime now) {
        admissionRepository.markWaitingExpired(admissionId, AdmissionStatus.WAITING_EXPIRED, now);
    }

    public Optional<ReservationRecord> findReservationForUpdate(long reservationId) {
        return reservationRepository.findForUpdate(reservationId).map(this::toReservationRecord);
    }

    public boolean confirmReservation(ReservationRecord reservation, LocalDateTime now) {
        if (reservation.status() == ReservationStatus.HELD
                && reservation.holdExpiresAt() != null
                && !reservation.holdExpiresAt().isAfter(now)) {
            return false;
        }
        if (reservation.status() == ReservationStatus.PAYMENT_UNKNOWN
                && (reservation.unknownInventoryDeadlineAt() == null
                || !reservation.unknownInventoryDeadlineAt().isAfter(now))) {
            return false;
        }

        int updated = reservationRepository.markConfirmed(reservation.id(), ReservationStatus.CONFIRMED, now);
        if (updated != 1) {
            return false;
        }
        if (reservation.status() == ReservationStatus.HELD) {
            inventoryRepository.confirmFromReserved(reservation.saleEventId(), reservation.productId());
        } else if (reservation.status() == ReservationStatus.PAYMENT_UNKNOWN) {
            inventoryRepository.confirmFromUnknown(reservation.saleEventId(), reservation.productId());
        }
        admissionRepository.markCompleted(reservation.admissionId(), AdmissionStatus.SUCCEEDED, now);
        return true;
    }

    public void releaseReservation(ReservationRecord reservation, String reason, LocalDateTime now) {
        int updated = reservationRepository.markReleased(reservation.id(), ReservationStatus.RELEASED, reason);
        if (updated != 1) {
            return;
        }
        if (reservation.status() == ReservationStatus.HELD) {
            decrementReserved(reservation.saleEventId(), reservation.productId());
        } else if (reservation.status() == ReservationStatus.PAYMENT_UNKNOWN) {
            inventoryRepository.decrementUnknown(reservation.saleEventId(), reservation.productId());
        }
        admissionRepository.markCompleted(reservation.admissionId(), AdmissionStatus.FAILED, now);
    }

    public void markPaymentUnknown(
            ReservationRecord reservation,
            LocalDateTime unknownDeadline,
            LocalDateTime firstUnknownAt,
            LocalDateTime activeReconcileUntil,
            String providerPaymentId
    ) {
        int updated = reservationRepository.markPaymentUnknown(
                reservation.id(),
                ReservationStatus.PAYMENT_UNKNOWN,
                unknownDeadline
        );
        if (updated != 1) {
            return;
        }
        inventoryRepository.moveReservedToUnknown(reservation.saleEventId(), reservation.productId());
        paymentAttemptRepository.markUnknown(
                reservation.bookingAttemptId(),
                PaymentAttemptStatus.PAYMENT_UNKNOWN,
                providerPaymentId,
                firstUnknownAt,
                activeReconcileUntil,
                firstUnknownAt
        );
    }

    public boolean createIdempotency(String bookingAttemptId, String requestHash, LocalDateTime expiresAt) {
        try {
            idempotencyRepository.saveAndFlush(IdempotencyRecordEntity.inProgress(
                    bookingAttemptId,
                    requestHash,
                    expiresAt
            ));
            return true;
        } catch (DataIntegrityViolationException ignored) {
            return false;
        }
    }

    public Optional<IdempotencyRecord> findIdempotency(String bookingAttemptId) {
        return idempotencyRepository.findByBookingAttemptId(bookingAttemptId).map(this::toIdempotencyRecord);
    }

    public void completeIdempotency(String bookingAttemptId, BookingResult result) {
        idempotencyRepository.complete(
                bookingAttemptId,
                idempotencyStatus(result),
                result.httpStatus(),
                result.businessCode(),
                serialize(result),
                result.reservationId()
        );
    }

    public void completeIdempotencyIfExists(String bookingAttemptId, BookingResult result) {
        idempotencyRepository.completeIfEmpty(
                bookingAttemptId,
                idempotencyStatus(result),
                result.httpStatus(),
                result.businessCode(),
                serialize(result),
                result.reservationId()
        );
    }

    public boolean createPaymentAttempt(
            String bookingAttemptId,
            long reservationId,
            String methodType,
            long amount,
            String providerOrderId
    ) {
        try {
            paymentAttemptRepository.saveAndFlush(PaymentAttemptEntity.requested(
                    bookingAttemptId,
                    reservationId,
                    methodType,
                    amount,
                    providerOrderId
            ));
            return true;
        } catch (DataIntegrityViolationException ignored) {
            return false;
        }
    }

    public boolean markPaymentConfirming(String bookingAttemptId, LocalDateTime now, LocalDateTime nextReconcileAt) {
        return paymentAttemptRepository.markConfirming(
                bookingAttemptId,
                PaymentAttemptStatus.CONFIRMING,
                now,
                nextReconcileAt
        ) == 1;
    }

    public Optional<PaymentAttemptRecord> findPaymentAttempt(String bookingAttemptId) {
        return paymentAttemptRepository.findByBookingAttemptId(bookingAttemptId).map(this::toPaymentAttemptRecord);
    }

    public void markPaymentConfirmed(String bookingAttemptId, String providerPaymentId) {
        paymentAttemptRepository.markConfirmed(bookingAttemptId, PaymentAttemptStatus.CONFIRMED, providerPaymentId);
    }

    public void markPaymentFailed(String bookingAttemptId, String errorCode) {
        paymentAttemptRepository.markFailed(bookingAttemptId, PaymentAttemptStatus.FAILED, errorCode);
    }

    public void markReconcilingAfterRelease(
            String bookingAttemptId,
            LocalDateTime nextReconcileAt,
            LocalDateTime activeReconcileUntil
    ) {
        paymentAttemptRepository.markStatusAndNextReconcile(
                bookingAttemptId,
                PaymentAttemptStatus.RECONCILING_AFTER_RELEASE,
                nextReconcileAt,
                activeReconcileUntil
        );
    }

    public void markLateSuccessCancelPending(String bookingAttemptId, LocalDateTime nextReconcileAt) {
        paymentAttemptRepository.markStatusAndNextReconcile(
                bookingAttemptId,
                PaymentAttemptStatus.LATE_SUCCESS_CANCEL_PENDING,
                nextReconcileAt,
                null
        );
    }

    public void markCancelledAfterRelease(String bookingAttemptId) {
        paymentAttemptRepository.markStatus(bookingAttemptId, PaymentAttemptStatus.CANCELLED_AFTER_RELEASE);
    }

    public void markManualReviewAfterWindow(LocalDateTime now) {
        paymentAttemptRepository.markManualReviewAfterWindow(
                PaymentAttemptStatus.MANUAL_REVIEW_REQUIRED,
                "active reconciliation window expired",
                now
        );
    }

    public boolean holdPoints(long userId, String bookingAttemptId, long pointAmount) {
        if (pointAmount == 0) {
            return true;
        }
        Long accountId = pointAccountRepository.findIdByUserId(userId).orElseThrow();
        try {
            pointHoldRepository.saveAndFlush(PointHoldEntity.held(bookingAttemptId, accountId, pointAmount));
        } catch (DataIntegrityViolationException ignored) {
            return true;
        }
        int updated = pointAccountRepository.holdPoints(userId, pointAmount);
        if (updated != 1) {
            pointHoldRepository.findByBookingAttemptIdAndStatus(bookingAttemptId, PointHoldStatus.HELD)
                    .ifPresent(hold -> pointHoldRepository.markStatus(hold.getId(), PointHoldStatus.RELEASED));
            return false;
        }
        return true;
    }

    public void capturePoints(String bookingAttemptId) {
        pointHoldRepository.findByBookingAttemptIdAndStatus(bookingAttemptId, PointHoldStatus.HELD)
                .ifPresent(hold -> {
                    pointAccountRepository.capturePoints(hold.getPointAccountId(), hold.getAmount());
                    pointHoldRepository.markStatus(hold.getId(), PointHoldStatus.CAPTURED);
                });
    }

    public void releasePoints(String bookingAttemptId) {
        pointHoldRepository.findByBookingAttemptIdAndStatus(bookingAttemptId, PointHoldStatus.HELD)
                .ifPresent(hold -> {
                    pointAccountRepository.releasePoints(hold.getPointAccountId(), hold.getAmount());
                    pointHoldRepository.markStatus(hold.getId(), PointHoldStatus.RELEASED);
                });
    }

    public InventorySnapshot inventory(long saleEventId, long productId) {
        return inventoryRepository.findBySaleEventIdAndProductId(saleEventId, productId)
                .map(inventory -> new InventorySnapshot(
                        inventory.getTotalCount(),
                        inventory.getReservedCount(),
                        inventory.getPaymentUnknownCount(),
                        inventory.getConfirmedCount()
                ))
                .orElseThrow();
    }

    public int countRows(String tableName) {
        Object result = entityManager.createNativeQuery("SELECT COUNT(*) FROM " + tableName).getSingleResult();
        return ((Number) result).intValue();
    }

    public List<ReservationRecord> staleHeldOrUnknown(LocalDateTime now, int limit) {
        @SuppressWarnings("unchecked")
        List<Object[]> rows = entityManager.createNativeQuery("""
                SELECT r.id, r.admission_id, r.booking_attempt_id, r.sale_event_id, r.product_id,
                       r.user_id, r.status, r.hold_expires_at, r.unknown_inventory_deadline_at
                FROM reservation r
                LEFT JOIN payment_attempt p ON p.booking_attempt_id = r.booking_attempt_id
                WHERE (
                    r.status = 'HELD' AND r.hold_expires_at <= :now
                ) OR (
                    r.status = 'PAYMENT_UNKNOWN'
                    AND p.next_reconcile_at IS NOT NULL
                    AND p.next_reconcile_at <= :now
                )
                ORDER BY r.id
                LIMIT :limit
                """)
                .setParameter("now", now)
                .setParameter("limit", limit)
                .getResultList();
        return rows.stream().map(this::toReservationRecord).toList();
    }

    public List<RecoveryClaim> claimDueRecoveries(
            LocalDateTime now,
            String leaseOwner,
            String leaseToken,
            LocalDateTime leaseUntil,
            int limit
    ) {
        @SuppressWarnings("unchecked")
        List<Object[]> rows = entityManager.createNativeQuery("""
                SELECT r.id, r.admission_id, r.booking_attempt_id, r.sale_event_id, r.product_id,
                       r.user_id, r.status, r.hold_expires_at, r.unknown_inventory_deadline_at,
                       p.id AS p_id, p.reservation_id AS p_reservation_id, p.status AS p_status,
                       p.provider_order_id AS p_provider_order_id,
                       p.provider_payment_id AS p_provider_payment_id,
                       p.first_unknown_at AS p_first_unknown_at,
                       p.active_reconcile_until AS p_active_reconcile_until,
                       p.next_reconcile_at AS p_next_reconcile_at,
                       p.reconcile_attempt_count AS p_reconcile_attempt_count,
                       p.confirm_started_at AS p_confirm_started_at,
                       p.lease_token AS p_lease_token
                FROM reservation r
                JOIN payment_attempt p ON p.booking_attempt_id = r.booking_attempt_id
                WHERE (
                    (r.status = 'HELD' AND r.hold_expires_at <= :now)
                    OR (
                        r.status = 'HELD'
                        AND p.status = 'CONFIRMING'
                        AND p.next_reconcile_at IS NOT NULL
                        AND p.next_reconcile_at <= :now
                    )
                    OR (
                        r.status = 'PAYMENT_UNKNOWN'
                        AND p.next_reconcile_at IS NOT NULL
                        AND p.next_reconcile_at <= :now
                    )
                    OR (
                        p.status IN ('RECONCILING_AFTER_RELEASE', 'LATE_SUCCESS_CANCEL_PENDING')
                        AND p.next_reconcile_at IS NOT NULL
                        AND p.next_reconcile_at <= :now
                    )
                )
                AND (p.lease_until IS NULL OR p.lease_until < :now)
                ORDER BY r.id
                LIMIT :limit
                FOR UPDATE SKIP LOCKED
                """)
                .setParameter("now", now)
                .setParameter("limit", limit)
                .getResultList();

        List<RecoveryClaim> claims = rows.stream()
                .map(row -> new RecoveryClaim(toReservationRecord(row), toPaymentAttemptRecord(row), leaseToken))
                .toList();
        for (RecoveryClaim claim : claims) {
            entityManager.createNativeQuery("""
                    UPDATE payment_attempt
                    SET lease_owner = :leaseOwner, lease_token = :leaseToken, lease_until = :leaseUntil
                    WHERE id = :paymentAttemptId
                    """)
                    .setParameter("leaseOwner", leaseOwner)
                    .setParameter("leaseToken", leaseToken)
                    .setParameter("leaseUntil", leaseUntil)
                    .setParameter("paymentAttemptId", claim.paymentAttempt().id())
                    .executeUpdate();
        }
        return claims;
    }

    public boolean leaseTokenMatches(String bookingAttemptId, String leaseToken) {
        return paymentAttemptRepository.countByBookingAttemptIdAndLeaseToken(bookingAttemptId, leaseToken) == 1;
    }

    public void scheduleNextReconcile(String bookingAttemptId, LocalDateTime nextReconcileAt, LocalDateTime now) {
        paymentAttemptRepository.scheduleNextReconcile(bookingAttemptId, nextReconcileAt, now);
    }

    private void decrementReserved(long saleEventId, long productId) {
        inventoryRepository.decrementReserved(saleEventId, productId);
    }

    private AdmissionDecision toAdmissionDecision(BookingAdmissionEntity admission) {
        Long dbSeq = admission.getDbAdmissionSeq();
        return AdmissionDecision.admitted(
                admission.getId(),
                dbSeq == null ? 0 : dbSeq,
                admission.getRedisSeq(),
                admission.getCandidateRank() == null ? 0 : admission.getCandidateRank(),
                admission.getGateMode()
        );
    }

    private AdmissionRecord toAdmissionRecord(BookingAdmissionEntity admission) {
        return new AdmissionRecord(
                admission.getId(),
                admission.getSaleEventId(),
                admission.getProductId(),
                admission.getUserId(),
                admission.getDbAdmissionSeq(),
                admission.getStatus(),
                admission.getBookingAttemptId(),
                admission.getWaitingExpiresAt()
        );
    }

    private ReservationRecord toReservationRecord(ReservationEntity reservation) {
        return new ReservationRecord(
                reservation.getId(),
                reservation.getAdmissionId(),
                reservation.getBookingAttemptId(),
                reservation.getSaleEventId(),
                reservation.getProductId(),
                reservation.getUserId(),
                reservation.getStatus(),
                reservation.getHoldExpiresAt(),
                reservation.getUnknownInventoryDeadlineAt()
        );
    }

    private ReservationRecord toReservationRecord(Object[] row) {
        return new ReservationRecord(
                longValue(row[0]),
                longValue(row[1]),
                (String) row[2],
                longValue(row[3]),
                longValue(row[4]),
                longValue(row[5]),
                ReservationStatus.valueOf((String) row[6]),
                dateTime(row[7]),
                dateTime(row[8])
        );
    }

    private IdempotencyRecord toIdempotencyRecord(IdempotencyRecordEntity record) {
        return new IdempotencyRecord(
                record.getBookingAttemptId(),
                record.getRequestHash(),
                record.getStatus(),
                record.getHttpStatus(),
                record.getBusinessCode(),
                record.getResponseSnapshot(),
                record.getReservationId()
        );
    }

    private PaymentAttemptRecord toPaymentAttemptRecord(PaymentAttemptEntity payment) {
        return new PaymentAttemptRecord(
                payment.getId(),
                payment.getBookingAttemptId(),
                payment.getReservationId(),
                payment.getStatus(),
                payment.getProviderOrderId(),
                payment.getProviderPaymentId(),
                payment.getFirstUnknownAt(),
                payment.getActiveReconcileUntil(),
                payment.getNextReconcileAt(),
                payment.getReconcileAttemptCount(),
                payment.getConfirmStartedAt(),
                payment.getLeaseToken()
        );
    }

    private PaymentAttemptRecord toPaymentAttemptRecord(Object[] row) {
        return new PaymentAttemptRecord(
                longValue(row[9]),
                (String) row[2],
                nullableLong(row[10]),
                PaymentAttemptStatus.valueOf((String) row[11]),
                (String) row[12],
                (String) row[13],
                dateTime(row[14]),
                dateTime(row[15]),
                dateTime(row[16]),
                intValue(row[17]),
                dateTime(row[18]),
                (String) row[19]
        );
    }

    private IdempotencyStatus idempotencyStatus(BookingResult result) {
        return result.httpStatus() >= 400 ? IdempotencyStatus.FAILED : IdempotencyStatus.COMPLETED;
    }

    private String serialize(BookingResult result) {
        try {
            return objectMapper.writeValueAsString(result);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize booking result", e);
        }
    }

    private static long longValue(Object value) {
        return ((Number) value).longValue();
    }

    private static Long nullableLong(Object value) {
        return value == null ? null : longValue(value);
    }

    private static int intValue(Object value) {
        return ((Number) value).intValue();
    }

    private static LocalDateTime dateTime(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof LocalDateTime localDateTime) {
            return localDateTime;
        }
        if (value instanceof Timestamp timestamp) {
            return timestamp.toLocalDateTime();
        }
        throw new IllegalArgumentException("Unsupported date/time value: " + value);
    }
}
