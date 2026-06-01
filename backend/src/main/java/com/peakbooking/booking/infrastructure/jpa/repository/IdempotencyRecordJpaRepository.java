package com.peakbooking.booking.infrastructure.jpa.repository;

import com.peakbooking.booking.domain.IdempotencyStatus;
import com.peakbooking.booking.infrastructure.jpa.entity.IdempotencyRecordEntity;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface IdempotencyRecordJpaRepository extends JpaRepository<IdempotencyRecordEntity, Long> {

    Optional<IdempotencyRecordEntity> findByBookingAttemptId(String bookingAttemptId);

    @Modifying
    @Query("""
            UPDATE IdempotencyRecordEntity record
            SET record.status = :status,
                record.httpStatus = :httpStatus,
                record.businessCode = :businessCode,
                record.responseSnapshot = :responseSnapshot,
                record.reservationId = :reservationId
            WHERE record.bookingAttemptId = :bookingAttemptId
            """)
    int complete(
            @Param("bookingAttemptId") String bookingAttemptId,
            @Param("status") IdempotencyStatus status,
            @Param("httpStatus") int httpStatus,
            @Param("businessCode") String businessCode,
            @Param("responseSnapshot") String responseSnapshot,
            @Param("reservationId") Long reservationId
    );

    @Modifying
    @Query("""
            UPDATE IdempotencyRecordEntity record
            SET record.status = :status,
                record.httpStatus = :httpStatus,
                record.businessCode = :businessCode,
                record.responseSnapshot = :responseSnapshot,
                record.reservationId = :reservationId
            WHERE record.bookingAttemptId = :bookingAttemptId
              AND record.httpStatus IS NULL
            """)
    int completeIfEmpty(
            @Param("bookingAttemptId") String bookingAttemptId,
            @Param("status") IdempotencyStatus status,
            @Param("httpStatus") int httpStatus,
            @Param("businessCode") String businessCode,
            @Param("responseSnapshot") String responseSnapshot,
            @Param("reservationId") Long reservationId
    );
}
