package com.peakbooking.booking.application;

import com.peakbooking.booking.domain.AdmissionDecision;
import com.peakbooking.booking.domain.GateMode;
import com.peakbooking.booking.infrastructure.jpa.BookingJpaRepository;
import java.time.Clock;
import java.time.LocalDateTime;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AdmissionTransactionService {

    private final BookingJpaRepository repository;
    private final Clock clock;

    public AdmissionTransactionService(BookingJpaRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    @Transactional
    public AdmissionDecision createAdmission(
            long saleEventId,
            long productId,
            long userId,
            String bookingAttemptId,
            GateMode gateMode,
            Long redisSeq,
            int candidateLimit
    ) {
        return repository.createAdmission(
                saleEventId,
                productId,
                userId,
                bookingAttemptId,
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
            String bookingAttemptId,
            int candidateLimit
    ) {
        repository.markDbFallback(saleEventId, productId);
        return repository.createAdmission(
                saleEventId,
                productId,
                userId,
                bookingAttemptId,
                GateMode.DB_FALLBACK,
                null,
                candidateLimit,
                LocalDateTime.now(clock)
        );
    }
}
