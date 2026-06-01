package com.peakbooking.booking.infrastructure.jpa.repository;

import com.peakbooking.booking.domain.PointHoldStatus;
import com.peakbooking.booking.infrastructure.jpa.entity.PointHoldEntity;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PointHoldJpaRepository extends JpaRepository<PointHoldEntity, Long> {

    Optional<PointHoldEntity> findByBookingAttemptIdAndStatus(String bookingAttemptId, PointHoldStatus status);

    @Modifying
    @Query("UPDATE PointHoldEntity hold SET hold.status = :status WHERE hold.id = :holdId")
    int markStatus(@Param("holdId") long holdId, @Param("status") PointHoldStatus status);
}
