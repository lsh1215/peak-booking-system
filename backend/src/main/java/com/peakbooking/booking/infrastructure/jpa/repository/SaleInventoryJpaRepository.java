package com.peakbooking.booking.infrastructure.jpa.repository;

import com.peakbooking.booking.infrastructure.jpa.entity.SaleInventoryEntity;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SaleInventoryJpaRepository extends JpaRepository<SaleInventoryEntity, Long> {

    Optional<SaleInventoryEntity> findBySaleEventIdAndProductId(long saleEventId, long productId);

    @Modifying
    @Query(value = """
            UPDATE sale_inventory
            SET reserved_count = reserved_count + 1
            WHERE sale_event_id = :saleEventId
              AND product_id = :productId
              AND reserved_count + payment_unknown_count + confirmed_count < total_count
            """, nativeQuery = true)
    int incrementReservedIfAvailable(@Param("saleEventId") long saleEventId, @Param("productId") long productId);

    @Modifying
    @Query(value = """
            UPDATE sale_inventory
            SET reserved_count = reserved_count - 1
            WHERE sale_event_id = :saleEventId AND product_id = :productId
            """, nativeQuery = true)
    int decrementReserved(@Param("saleEventId") long saleEventId, @Param("productId") long productId);

    @Modifying
    @Query(value = """
            UPDATE sale_inventory
            SET reserved_count = reserved_count - 1,
                confirmed_count = confirmed_count + 1
            WHERE sale_event_id = :saleEventId AND product_id = :productId
            """, nativeQuery = true)
    int confirmFromReserved(@Param("saleEventId") long saleEventId, @Param("productId") long productId);

    @Modifying
    @Query(value = """
            UPDATE sale_inventory
            SET payment_unknown_count = payment_unknown_count - 1,
                confirmed_count = confirmed_count + 1
            WHERE sale_event_id = :saleEventId AND product_id = :productId
            """, nativeQuery = true)
    int confirmFromUnknown(@Param("saleEventId") long saleEventId, @Param("productId") long productId);

    @Modifying
    @Query(value = """
            UPDATE sale_inventory
            SET reserved_count = reserved_count - 1,
                payment_unknown_count = payment_unknown_count + 1
            WHERE sale_event_id = :saleEventId AND product_id = :productId
            """, nativeQuery = true)
    int moveReservedToUnknown(@Param("saleEventId") long saleEventId, @Param("productId") long productId);

    @Modifying
    @Query(value = """
            UPDATE sale_inventory
            SET payment_unknown_count = payment_unknown_count - 1
            WHERE sale_event_id = :saleEventId AND product_id = :productId
            """, nativeQuery = true)
    int decrementUnknown(@Param("saleEventId") long saleEventId, @Param("productId") long productId);
}
