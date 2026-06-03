package com.peakbooking.booking.application;

import com.peakbooking.booking.domain.AdmissionDecision;
import com.peakbooking.booking.domain.GateMode;
import com.peakbooking.booking.infrastructure.jpa.BookingJpaRepository;
import java.time.Clock;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AdmissionTransactionService {

    private final BookingJpaRepository repository;
    private final Clock clock;

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
    public AdmissionDecision createLocalQueueAdmission(
            long saleEventId,
            long productId,
            long userId,
            String bookingAttemptId,
            int candidateLimit
    ) {
        return repository.createLocalQueueAdmission(
                saleEventId,
                productId,
                userId,
                bookingAttemptId,
                candidateLimit,
                LocalDateTime.now(clock)
        );
    }

    @Transactional
    public void markRedisFailoverPaused(long saleEventId, long productId) {
        repository.markRedisFailoverPaused(saleEventId, productId);
    }

    @Transactional
    public void markRedisRecovered(long saleEventId, long productId) {
        repository.markRedisRecovered(saleEventId, productId);
    }
}
