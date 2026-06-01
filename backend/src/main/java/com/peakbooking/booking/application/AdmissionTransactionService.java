package com.peakbooking.booking.application;

import com.peakbooking.booking.domain.AdmissionDecision;
import com.peakbooking.booking.domain.GateMode;
import com.peakbooking.booking.infrastructure.jdbc.BookingJdbcRepository;
import java.time.Clock;
import java.time.LocalDateTime;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AdmissionTransactionService {

    private final BookingJdbcRepository repository;
    private final Clock clock;

    public AdmissionTransactionService(BookingJdbcRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    @Transactional
    public AdmissionDecision createAdmission(
            long saleEventId,
            long productId,
            long userId,
            GateMode gateMode,
            Long redisSeq,
            int candidateLimit
    ) {
        return repository.createAdmission(
                saleEventId,
                productId,
                userId,
                gateMode,
                redisSeq,
                candidateLimit,
                LocalDateTime.now(clock)
        );
    }

    @Transactional
    public AdmissionDecision markFallbackAndCreate(
            long saleEventId,
            long productId,
            long userId,
            int candidateLimit
    ) {
        repository.markDbFallback(saleEventId, productId);
        return repository.createAdmission(
                saleEventId,
                productId,
                userId,
                GateMode.DB_FALLBACK,
                null,
                candidateLimit,
                LocalDateTime.now(clock)
        );
    }
}
