package com.peakbooking.booking.infrastructure.jpa.repository;

import com.peakbooking.booking.infrastructure.jpa.entity.PointAccountEntity;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PointAccountJpaRepository extends JpaRepository<PointAccountEntity, Long> {

    @Query("SELECT COALESCE(MAX(account.availablePoints), 0) FROM PointAccountEntity account WHERE account.userId = :userId")
    long availablePoints(@Param("userId") long userId);

    @Query("SELECT account.id FROM PointAccountEntity account WHERE account.userId = :userId")
    Optional<Long> findIdByUserId(@Param("userId") long userId);

    @Modifying
    @Query("""
            UPDATE PointAccountEntity account
            SET account.availablePoints = account.availablePoints - :amount,
                account.heldPoints = account.heldPoints + :amount
            WHERE account.userId = :userId AND account.availablePoints >= :amount
            """)
    int holdPoints(@Param("userId") long userId, @Param("amount") long amount);

    @Modifying
    @Query("""
            UPDATE PointAccountEntity account
            SET account.heldPoints = account.heldPoints - :amount
            WHERE account.id = :accountId
            """)
    int capturePoints(@Param("accountId") long accountId, @Param("amount") long amount);

    @Modifying
    @Query("""
            UPDATE PointAccountEntity account
            SET account.availablePoints = account.availablePoints + :amount,
                account.heldPoints = account.heldPoints - :amount
            WHERE account.id = :accountId
            """)
    int releasePoints(@Param("accountId") long accountId, @Param("amount") long amount);
}
