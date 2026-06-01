package com.peakbooking.booking.infrastructure.jpa.repository;

import com.peakbooking.booking.domain.ReservationStatus;
import com.peakbooking.booking.infrastructure.jpa.entity.ReservationEntity;
import jakarta.persistence.LockModeType;
import java.time.LocalDateTime;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ReservationJpaRepository extends JpaRepository<ReservationEntity, Long> {

    Optional<ReservationEntity> findByBookingAttemptId(String bookingAttemptId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT reservation FROM ReservationEntity reservation WHERE reservation.id = :reservationId")
    Optional<ReservationEntity> findForUpdate(@Param("reservationId") long reservationId);

    @Modifying
    @Query("""
            UPDATE ReservationEntity reservation
            SET reservation.status = :status,
                reservation.confirmedAt = :now
            WHERE reservation.id = :reservationId
              AND reservation.status IN (com.peakbooking.booking.domain.ReservationStatus.HELD,
                                         com.peakbooking.booking.domain.ReservationStatus.PAYMENT_UNKNOWN)
              AND (reservation.status = com.peakbooking.booking.domain.ReservationStatus.HELD
                   OR reservation.unknownInventoryDeadlineAt > :now)
            """)
    int markConfirmed(
            @Param("reservationId") long reservationId,
            @Param("status") ReservationStatus status,
            @Param("now") LocalDateTime now
    );

    @Modifying
    @Query("""
            UPDATE ReservationEntity reservation
            SET reservation.status = :status,
                reservation.releasedReason = :reason
            WHERE reservation.id = :reservationId
              AND reservation.status IN (com.peakbooking.booking.domain.ReservationStatus.HELD,
                                         com.peakbooking.booking.domain.ReservationStatus.PAYMENT_UNKNOWN)
            """)
    int markReleased(
            @Param("reservationId") long reservationId,
            @Param("status") ReservationStatus status,
            @Param("reason") String reason
    );

    @Modifying
    @Query("""
            UPDATE ReservationEntity reservation
            SET reservation.status = :status,
                reservation.unknownInventoryDeadlineAt = :unknownDeadline
            WHERE reservation.id = :reservationId
              AND reservation.status = com.peakbooking.booking.domain.ReservationStatus.HELD
            """)
    int markPaymentUnknown(
            @Param("reservationId") long reservationId,
            @Param("status") ReservationStatus status,
            @Param("unknownDeadline") LocalDateTime unknownDeadline
    );
}
