package com.peakbooking.booking.infrastructure.jpa.repository;

import com.peakbooking.booking.infrastructure.jpa.entity.MockPgPaymentEntity;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MockPgPaymentJpaRepository extends JpaRepository<MockPgPaymentEntity, Long> {

    Optional<MockPgPaymentEntity> findByProviderOrderId(String providerOrderId);

    Optional<MockPgPaymentEntity> findByProviderPaymentId(String providerPaymentId);

    @Modifying
    @Query(value = """
            UPDATE mock_pg_payment
            SET confirm_count = confirm_count + 1
            WHERE provider_order_id = :providerOrderId
            """, nativeQuery = true)
    int incrementConfirmCount(@Param("providerOrderId") String providerOrderId);

    @Modifying
    @Query(value = """
            UPDATE mock_pg_payment
            SET status = 'CANCELLED',
                cancel_count = cancel_count + 1,
                last_error_code = :reason
            WHERE provider_payment_id = :providerPaymentId AND status <> 'CANCELLED'
            """, nativeQuery = true)
    int cancelByPaymentId(@Param("providerPaymentId") String providerPaymentId, @Param("reason") String reason);

    @Modifying
    @Query(value = """
            UPDATE mock_pg_payment
            SET status = 'CANCELLED',
                cancel_count = cancel_count + 1,
                last_error_code = :reason
            WHERE provider_order_id = :providerOrderId AND status <> 'CANCELLED'
            """, nativeQuery = true)
    int cancelByOrderId(@Param("providerOrderId") String providerOrderId, @Param("reason") String reason);

    @Query(value = """
            SELECT COALESCE(MAX(confirm_count), 0)
            FROM mock_pg_payment
            WHERE provider_order_id = :providerOrderId
            """, nativeQuery = true)
    int confirmCount(@Param("providerOrderId") String providerOrderId);
}
