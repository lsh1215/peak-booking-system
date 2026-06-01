package com.peakbooking.booking.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.peakbooking.booking.domain.AdmissionDecision;
import com.peakbooking.booking.domain.AdmissionResult;
import com.peakbooking.booking.domain.GateMode;
import com.peakbooking.booking.infrastructure.jpa.BookingJpaRepository;
import com.peakbooking.booking.redis.RedisAdmissionGateway;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.RedisSystemException;

class BookingAdmissionServiceTest {

    @Test
    void should_not_read_gate_mode_from_db_for_every_healthy_redis_admission() {
        BookingJpaRepository repository = mock(BookingJpaRepository.class);
        RedisAdmissionGateway redisAdmissionGateway = mock(RedisAdmissionGateway.class);
        AdmissionTransactionService transactionService = mock(AdmissionTransactionService.class);
        BookingAdmissionService service = new BookingAdmissionService(
                BookingApplicationServiceTest.propertiesWithBulkhead(6, 2, 5, 1, 2),
                repository,
                redisAdmissionGateway,
                transactionService,
                new BookingDbWriteBulkhead(BookingApplicationServiceTest.propertiesWithBulkhead(6, 2, 5, 1, 2)),
                Duration.ofSeconds(1)
        );
        when(repository.gateMode(1, 1)).thenReturn(GateMode.REDIS);
        when(redisAdmissionGateway.tryAdmit(1, 1, 101, 30))
                .thenReturn(new RedisAdmissionGateway.Result(false, 0));
        when(redisAdmissionGateway.tryAdmit(1, 1, 102, 30))
                .thenReturn(new RedisAdmissionGateway.Result(false, 0));

        service.admit(1, 1, 101, "attempt-101");
        service.admit(1, 1, 102, "attempt-102");

        verify(repository, times(1)).gateMode(1, 1);
        verify(redisAdmissionGateway).tryAdmit(1, 1, 101, 30);
        verify(redisAdmissionGateway).tryAdmit(1, 1, 102, 30);
        verifyNoInteractions(transactionService);
    }

    @Test
    void should_retry_once_before_switching_healthy_redis_to_db_fallback() {
        BookingJpaRepository repository = mock(BookingJpaRepository.class);
        RedisAdmissionGateway redisAdmissionGateway = mock(RedisAdmissionGateway.class);
        AdmissionTransactionService transactionService = mock(AdmissionTransactionService.class);
        BookingAdmissionService service = new BookingAdmissionService(
                BookingApplicationServiceTest.propertiesWithBulkhead(6, 2, 5, 1, 2),
                repository,
                redisAdmissionGateway,
                transactionService,
                new BookingDbWriteBulkhead(BookingApplicationServiceTest.propertiesWithBulkhead(6, 2, 5, 1, 2)),
                Duration.ofSeconds(1)
        );
        when(repository.gateMode(1, 1)).thenReturn(GateMode.REDIS);
        when(redisAdmissionGateway.tryAdmit(1, 1, 101, 30))
                .thenThrow(new RedisSystemException("transient redis timeout", new RuntimeException("timeout")))
                .thenReturn(new RedisAdmissionGateway.Result(true, 1));
        when(transactionService.createAdmission(1, 1, 101, "attempt-101", GateMode.REDIS, 1L, 30))
                .thenReturn(AdmissionDecision.admitted(10, 1, 1L, 1, GateMode.REDIS));

        AdmissionDecision decision = service.admit(1, 1, 101, "attempt-101");

        assertThat(decision.gateMode()).isEqualTo(GateMode.REDIS);
        assertThat(decision.redisSeq()).isEqualTo(1L);
        verify(redisAdmissionGateway, times(2)).tryAdmit(1, 1, 101, 30);
        verify(transactionService).createAdmission(1, 1, 101, "attempt-101", GateMode.REDIS, 1L, 30);
        verify(transactionService, never()).markFallbackAndCreate(1, 1, 101, "attempt-101", 30);
    }

    @Test
    void should_not_switch_to_db_fallback_on_redis_command_timeout_after_retry() {
        BookingJpaRepository repository = mock(BookingJpaRepository.class);
        RedisAdmissionGateway redisAdmissionGateway = mock(RedisAdmissionGateway.class);
        AdmissionTransactionService transactionService = mock(AdmissionTransactionService.class);
        BookingAdmissionService service = new BookingAdmissionService(
                BookingApplicationServiceTest.propertiesWithBulkhead(6, 2, 5, 1, 2),
                repository,
                redisAdmissionGateway,
                transactionService,
                new BookingDbWriteBulkhead(BookingApplicationServiceTest.propertiesWithBulkhead(6, 2, 5, 1, 2)),
                Duration.ofSeconds(1)
        );
        when(repository.gateMode(1, 1)).thenReturn(GateMode.REDIS);
        when(redisAdmissionGateway.tryAdmit(1, 1, 101, 30))
                .thenThrow(new QueryTimeoutException("transient redis timeout"))
                .thenThrow(new QueryTimeoutException("transient redis timeout"));

        AdmissionDecision decision = service.admit(1, 1, 101, "attempt-101");

        assertThat(decision.result()).isEqualTo(AdmissionResult.REJECTED);
        assertThat(decision.gateMode()).isEqualTo(GateMode.REDIS);
        verify(redisAdmissionGateway, times(2)).tryAdmit(1, 1, 101, 30);
        verify(transactionService, never()).markFallbackAndCreate(1, 1, 101, "attempt-101", 30);
        verifyNoInteractions(transactionService);
    }
}
