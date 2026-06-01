package com.peakbooking.booking.infrastructure.jpa.repository;

import com.peakbooking.booking.domain.AdmissionStatus;
import com.peakbooking.booking.infrastructure.jpa.entity.BookingAdmissionEntity;
import java.time.LocalDateTime;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface BookingAdmissionJpaRepository extends JpaRepository<BookingAdmissionEntity, Long> {

    Optional<BookingAdmissionEntity> findBySaleEventIdAndProductIdAndUserId(
            long saleEventId,
            long productId,
            long userId
    );

    Optional<BookingAdmissionEntity> findByBookingAttemptId(String bookingAttemptId);

    @Modifying
    @Query("""
            UPDATE BookingAdmissionEntity admission
            SET admission.status = :status,
                admission.waitingExpiresAt = COALESCE(admission.waitingExpiresAt, :waitingExpiresAt)
            WHERE admission.id = :admissionId
              AND admission.status IN (com.peakbooking.booking.domain.AdmissionStatus.ADMITTED,
                                       com.peakbooking.booking.domain.AdmissionStatus.WAITING_CANDIDATE)
            """)
    int markWaiting(
            @Param("admissionId") long admissionId,
            @Param("status") AdmissionStatus status,
            @Param("waitingExpiresAt") LocalDateTime waitingExpiresAt
    );

    @Modifying
    @Query("""
            UPDATE BookingAdmissionEntity admission
            SET admission.bookingAttemptId = COALESCE(admission.bookingAttemptId, :bookingAttemptId)
            WHERE admission.id = :admissionId
            """)
    int attachAttempt(
            @Param("admissionId") long admissionId,
            @Param("bookingAttemptId") String bookingAttemptId
    );

    @Query("""
            SELECT COUNT(other)
            FROM BookingAdmissionEntity target
            JOIN BookingAdmissionEntity other
              ON other.saleEventId = target.saleEventId
             AND other.productId = target.productId
            WHERE target.id = :admissionId
              AND other.dbAdmissionSeq < target.dbAdmissionSeq
              AND other.status = com.peakbooking.booking.domain.AdmissionStatus.WAITING_CANDIDATE
              AND other.waitingExpiresAt > :now
            """)
    long countEarlierWaitingCandidates(@Param("admissionId") long admissionId, @Param("now") LocalDateTime now);

    @Modifying
    @Query("""
            UPDATE BookingAdmissionEntity admission
            SET admission.status = :status,
                admission.completedAt = :now
            WHERE admission.id = :admissionId
              AND admission.status = com.peakbooking.booking.domain.AdmissionStatus.WAITING_CANDIDATE
            """)
    int markWaitingExpired(
            @Param("admissionId") long admissionId,
            @Param("status") AdmissionStatus status,
            @Param("now") LocalDateTime now
    );

    @Modifying
    @Query("""
            UPDATE BookingAdmissionEntity admission
            SET admission.status = :status,
                admission.completedAt = :now
            WHERE admission.id = :admissionId
            """)
    int markCompleted(
            @Param("admissionId") long admissionId,
            @Param("status") AdmissionStatus status,
            @Param("now") LocalDateTime now
    );
}
