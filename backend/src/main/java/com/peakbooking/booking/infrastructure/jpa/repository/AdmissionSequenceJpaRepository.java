package com.peakbooking.booking.infrastructure.jpa.repository;

import com.peakbooking.booking.infrastructure.jpa.entity.AdmissionSequenceEntity;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AdmissionSequenceJpaRepository extends JpaRepository<AdmissionSequenceEntity, Long> {

    Optional<AdmissionSequenceEntity> findBySaleEventIdAndProductId(long saleEventId, long productId);

    @Modifying
    @Query(value = """
            INSERT INTO admission_sequence (sale_event_id, product_id, next_seq, gate_mode)
            VALUES (:saleEventId, :productId, 0, 'REDIS')
            ON DUPLICATE KEY UPDATE id = id
            """, nativeQuery = true)
    int ensure(@Param("saleEventId") long saleEventId, @Param("productId") long productId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            SELECT sequence
            FROM AdmissionSequenceEntity sequence
            WHERE sequence.saleEventId = :saleEventId AND sequence.productId = :productId
            """)
    Optional<AdmissionSequenceEntity> findForUpdate(
            @Param("saleEventId") long saleEventId,
            @Param("productId") long productId
    );

    @Modifying
    @Query(value = """
            UPDATE admission_sequence
            SET gate_mode = 'DB_FALLBACK'
            WHERE sale_event_id = :saleEventId AND product_id = :productId
            """, nativeQuery = true)
    int markDbFallback(@Param("saleEventId") long saleEventId, @Param("productId") long productId);

    @Modifying
    @Query(value = """
            UPDATE admission_sequence
            SET next_seq = next_seq + 1
            WHERE sale_event_id = :saleEventId AND product_id = :productId AND next_seq < :candidateLimit
            """, nativeQuery = true)
    int incrementIfUnderLimit(
            @Param("saleEventId") long saleEventId,
            @Param("productId") long productId,
            @Param("candidateLimit") int candidateLimit
    );

    @Query(value = """
            SELECT next_seq
            FROM admission_sequence
            WHERE sale_event_id = :saleEventId AND product_id = :productId
            """, nativeQuery = true)
    Long nextSeq(@Param("saleEventId") long saleEventId, @Param("productId") long productId);
}
