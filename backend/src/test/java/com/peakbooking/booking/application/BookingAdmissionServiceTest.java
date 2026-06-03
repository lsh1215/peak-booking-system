package com.peakbooking.booking.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.peakbooking.booking.config.BookingProperties;
import com.peakbooking.booking.domain.AdmissionDecision;
import com.peakbooking.booking.domain.AdmissionResult;
import com.peakbooking.booking.domain.BookingErrorCode;
import com.peakbooking.booking.domain.GateMode;
import com.peakbooking.booking.infrastructure.jpa.BookingJpaRepository;
import com.peakbooking.booking.redis.RedisAdmissionGateway;
import com.peakbooking.common.exception.BusinessException;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.RedisSystemException;

class BookingAdmissionServiceTest {

    @Test
    void should_not_read_gate_mode_from_db_for_every_healthy_redis_admission() {
        BookingJpaRepository repository = mock(BookingJpaRepository.class);
        RedisAdmissionGateway redisAdmissionGateway = mock(RedisAdmissionGateway.class);
        AdmissionTransactionService transactionService = mock(AdmissionTransactionService.class);
        BookingProperties properties = BookingApplicationServiceTest.propertiesWithBulkhead(
                6, 5, 1, 2, 1, Duration.ofSeconds(2)
        );
        BookingAdmissionService service = new BookingAdmissionService(
                properties,
                repository,
                redisAdmissionGateway,
                transactionService,
                new BookingDbWriteBulkhead(properties),
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
    void should_not_consume_redis_candidate_when_admission_persistence_budget_is_full() {
        BookingJpaRepository repository = mock(BookingJpaRepository.class);
        RedisAdmissionGateway redisAdmissionGateway = mock(RedisAdmissionGateway.class);
        AdmissionTransactionService transactionService = mock(AdmissionTransactionService.class);
        BookingAdmissionService service = new BookingAdmissionService(
                BookingApplicationServiceTest.propertiesWithBulkhead(0, 5, 1, 2),
                repository,
                redisAdmissionGateway,
                transactionService,
                new BookingDbWriteBulkhead(BookingApplicationServiceTest.propertiesWithBulkhead(0, 5, 1, 2)),
                Duration.ofSeconds(1)
        );
        when(repository.gateMode(1, 1)).thenReturn(GateMode.REDIS);

        assertThatThrownBy(() -> service.admit(1, 1, 101, "attempt-101"))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(BookingErrorCode.SERVICE_BUSY));

        verifyNoInteractions(redisAdmissionGateway);
        verifyNoInteractions(transactionService);
    }

    @Test
    void should_reject_paused_gate_when_half_open_probe_is_not_ready() {
        BookingJpaRepository repository = mock(BookingJpaRepository.class);
        RedisAdmissionGateway redisAdmissionGateway = mock(RedisAdmissionGateway.class);
        AdmissionTransactionService transactionService = mock(AdmissionTransactionService.class);
        BookingAdmissionService service = new BookingAdmissionService(
                BookingApplicationServiceTest.propertiesWithBulkhead(0, 5, 1, 2),
                repository,
                redisAdmissionGateway,
                transactionService,
                new BookingDbWriteBulkhead(BookingApplicationServiceTest.propertiesWithBulkhead(0, 5, 1, 2)),
                Duration.ofSeconds(1)
        );
        when(repository.gateMode(1, 1)).thenReturn(GateMode.REDIS_FAILOVER_PAUSED);

        AdmissionDecision decision = service.admit(1, 1, 101, "attempt-101");

        assertThat(decision.result()).isEqualTo(AdmissionResult.REJECTED);
        assertThat(decision.gateMode()).isEqualTo(GateMode.REDIS_FAILOVER_PAUSED);
        verify(redisAdmissionGateway).probeRecovery();
        verify(redisAdmissionGateway, never()).tryAdmit(1, 1, 101, 30);
        verifyNoInteractions(transactionService);
    }

    @Test
    void should_reopen_paused_gate_after_cache_ttl_only_after_half_open_probe_succeeds() throws Exception {
        BookingJpaRepository repository = mock(BookingJpaRepository.class);
        RedisAdmissionGateway redisAdmissionGateway = mock(RedisAdmissionGateway.class);
        AdmissionTransactionService transactionService = mock(AdmissionTransactionService.class);
        BookingAdmissionService service = new BookingAdmissionService(
                BookingApplicationServiceTest.propertiesWithBulkhead(
                        6, 5, 1, 2, 16, Duration.ofMillis(1)
                ),
                repository,
                redisAdmissionGateway,
                transactionService,
                new BookingDbWriteBulkhead(BookingApplicationServiceTest.propertiesWithBulkhead(
                        6, 5, 1, 2, 16, Duration.ofMillis(1)
                )),
                Duration.ofMillis(1)
        );
        when(repository.gateMode(1, 1))
                .thenReturn(GateMode.REDIS)
                .thenReturn(GateMode.REDIS_FAILOVER_PAUSED);
        when(redisAdmissionGateway.tryAdmit(1, 1, 101, 30))
                .thenThrow(new QueryTimeoutException("redis timed out"));
        when(redisAdmissionGateway.probeRecovery()).thenReturn(true);
        when(redisAdmissionGateway.tryAdmit(1, 1, 102, 30))
                .thenReturn(new RedisAdmissionGateway.Result(false, 0));

        AdmissionDecision paused = service.admit(1, 1, 101, "attempt-101");
        Thread.sleep(5);
        AdmissionDecision reopened = service.admit(1, 1, 102, "attempt-102");

        assertThat(paused.gateMode()).isEqualTo(GateMode.REDIS_FAILOVER_PAUSED);
        assertThat(reopened.gateMode()).isEqualTo(GateMode.REDIS);
        assertThat(reopened.result()).isEqualTo(AdmissionResult.REJECTED);
        verify(redisAdmissionGateway).probeRecovery();
        verify(transactionService).markRedisFailoverPaused(1, 1);
        verify(transactionService).markRedisRecovered(1, 1);
        verify(redisAdmissionGateway).tryAdmit(1, 1, 102, 30);
    }

    @Test
    void should_reopen_after_detected_redis_failover_when_half_open_probe_succeeds() throws Exception {
        BookingJpaRepository repository = mock(BookingJpaRepository.class);
        RedisAdmissionGateway redisAdmissionGateway = mock(RedisAdmissionGateway.class);
        AdmissionTransactionService transactionService = mock(AdmissionTransactionService.class);
        BookingProperties properties = BookingApplicationServiceTest.propertiesWithBulkhead(
                6, 5, 1, 2, 16, Duration.ofMillis(1)
        );
        BookingAdmissionService service = new BookingAdmissionService(
                properties,
                repository,
                redisAdmissionGateway,
                transactionService,
                new BookingDbWriteBulkhead(properties),
                Duration.ofMillis(1)
        );
        when(repository.gateMode(1, 1))
                .thenReturn(GateMode.REDIS)
                .thenReturn(GateMode.REDIS_FAILOVER_PAUSED);
        when(redisAdmissionGateway.tryAdmit(1, 1, 101, 30))
                .thenThrow(new QueryTimeoutException("redis timed out"));
        when(redisAdmissionGateway.probeRecovery()).thenReturn(true);
        when(redisAdmissionGateway.tryAdmit(1, 1, 102, 30))
                .thenReturn(new RedisAdmissionGateway.Result(false, 0));

        AdmissionDecision paused = service.admit(1, 1, 101, "attempt-101");
        Thread.sleep(5);
        AdmissionDecision reopened = service.admit(1, 1, 102, "attempt-102");

        assertThat(paused.gateMode()).isEqualTo(GateMode.REDIS_FAILOVER_PAUSED);
        assertThat(reopened.gateMode()).isEqualTo(GateMode.REDIS);
        verify(transactionService).markRedisFailoverPaused(1, 1);
        verify(transactionService).markRedisRecovered(1, 1);
        verify(redisAdmissionGateway).probeRecovery();
        verify(redisAdmissionGateway).tryAdmit(1, 1, 102, 30);
    }

    @Test
    void should_keep_paused_after_cache_ttl_when_half_open_probe_fails() throws Exception {
        BookingJpaRepository repository = mock(BookingJpaRepository.class);
        RedisAdmissionGateway redisAdmissionGateway = mock(RedisAdmissionGateway.class);
        AdmissionTransactionService transactionService = mock(AdmissionTransactionService.class);
        BookingProperties properties = BookingApplicationServiceTest.propertiesWithBulkhead(
                6, 5, 1, 2, 16, Duration.ofMillis(1)
        );
        BookingAdmissionService service = new BookingAdmissionService(
                properties,
                repository,
                redisAdmissionGateway,
                transactionService,
                new BookingDbWriteBulkhead(properties),
                Duration.ofMillis(1)
        );
        when(repository.gateMode(1, 1))
                .thenReturn(GateMode.REDIS)
                .thenReturn(GateMode.REDIS_FAILOVER_PAUSED);
        when(redisAdmissionGateway.tryAdmit(1, 1, 101, 30))
                .thenThrow(new QueryTimeoutException("redis timed out"));
        when(redisAdmissionGateway.probeRecovery())
                .thenThrow(new QueryTimeoutException("redis still timed out"));

        AdmissionDecision paused = service.admit(1, 1, 101, "attempt-101");
        Thread.sleep(5);
        AdmissionDecision stillPaused = service.admit(1, 1, 102, "attempt-102");

        assertThat(paused.gateMode()).isEqualTo(GateMode.REDIS_FAILOVER_PAUSED);
        assertThat(stillPaused.gateMode()).isEqualTo(GateMode.REDIS_FAILOVER_PAUSED);
        assertThat(stillPaused.result()).isEqualTo(AdmissionResult.REJECTED);
        verify(redisAdmissionGateway).probeRecovery();
        verify(redisAdmissionGateway, never()).tryAdmit(1, 1, 102, 30);
        verify(transactionService, never()).markRedisRecovered(1, 1);
        verify(transactionService).markRedisFailoverPaused(1, 1);
    }

    @Test
    void should_not_probe_again_before_local_pause_retry_after_expires() throws Exception {
        BookingJpaRepository repository = mock(BookingJpaRepository.class);
        RedisAdmissionGateway redisAdmissionGateway = mock(RedisAdmissionGateway.class);
        AdmissionTransactionService transactionService = mock(AdmissionTransactionService.class);
        BookingProperties properties = BookingApplicationServiceTest.propertiesWithBulkhead(
                6, 5, 1, 2, 16, Duration.ofMillis(100)
        );
        BookingAdmissionService service = new BookingAdmissionService(
                properties,
                repository,
                redisAdmissionGateway,
                transactionService,
                new BookingDbWriteBulkhead(properties),
                Duration.ofMillis(1)
        );
        when(repository.gateMode(1, 1))
                .thenReturn(GateMode.REDIS)
                .thenReturn(GateMode.REDIS_FAILOVER_PAUSED);
        when(redisAdmissionGateway.tryAdmit(1, 1, 101, 30))
                .thenThrow(new QueryTimeoutException("redis timed out"));
        when(redisAdmissionGateway.probeRecovery())
                .thenThrow(new QueryTimeoutException("redis still timed out"));

        service.admit(1, 1, 101, "attempt-101");
        Thread.sleep(5);
        AdmissionDecision failedProbe = service.admit(1, 1, 102, "attempt-102");
        AdmissionDecision suppressed = service.admit(1, 1, 103, "attempt-103");

        assertThat(failedProbe.gateMode()).isEqualTo(GateMode.REDIS_FAILOVER_PAUSED);
        assertThat(suppressed.gateMode()).isEqualTo(GateMode.REDIS_FAILOVER_PAUSED);
        verify(redisAdmissionGateway, never()).probeRecovery();
        verify(redisAdmissionGateway, never()).tryAdmit(1, 1, 103, 30);
        verify(repository, times(1)).gateMode(1, 1);
    }

    @Test
    void should_pause_on_first_redis_timeout_without_request_path_retry_or_ping() {
        BookingJpaRepository repository = mock(BookingJpaRepository.class);
        RedisAdmissionGateway redisAdmissionGateway = mock(RedisAdmissionGateway.class);
        AdmissionTransactionService transactionService = mock(AdmissionTransactionService.class);
        BookingAdmissionService service = new BookingAdmissionService(
                BookingApplicationServiceTest.propertiesWithBulkhead(
                        6, 5, 1, 2, 1, Duration.ofSeconds(2)
                ),
                repository,
                redisAdmissionGateway,
                transactionService,
                new BookingDbWriteBulkhead(BookingApplicationServiceTest.propertiesWithBulkhead(
                        6, 5, 1, 2, 1, Duration.ofSeconds(2)
                )),
                Duration.ofSeconds(1)
        );
        when(repository.gateMode(1, 1)).thenReturn(GateMode.REDIS);
        when(redisAdmissionGateway.tryAdmit(1, 1, 101, 30))
                .thenThrow(new RedisSystemException("transient redis timeout", new RuntimeException("timeout")));

        AdmissionDecision decision = service.admit(1, 1, 101, "attempt-101");

        assertThat(decision.result()).isEqualTo(AdmissionResult.REJECTED);
        assertThat(decision.gateMode()).isEqualTo(GateMode.REDIS_FAILOVER_PAUSED);
        verify(redisAdmissionGateway, times(1)).tryAdmit(1, 1, 101, 30);
        verify(redisAdmissionGateway, never()).ping();
        verify(transactionService, never()).createAdmission(1, 1, 101, "attempt-101", GateMode.REDIS, 1L, 30);
        verify(transactionService).markRedisFailoverPaused(1, 1);
    }

    @Test
    void should_pause_without_retry_when_redis_replication_is_not_confirmed() {
        BookingJpaRepository repository = mock(BookingJpaRepository.class);
        RedisAdmissionGateway redisAdmissionGateway = mock(RedisAdmissionGateway.class);
        AdmissionTransactionService transactionService = mock(AdmissionTransactionService.class);
        BookingProperties properties = BookingApplicationServiceTest.propertiesWithBulkhead(
                6, 5, 1, 2, 1, Duration.ofSeconds(2)
        );
        BookingAdmissionService service = new BookingAdmissionService(
                properties,
                repository,
                redisAdmissionGateway,
                transactionService,
                new BookingDbWriteBulkhead(properties),
                Duration.ofSeconds(1)
        );
        when(repository.gateMode(1, 1)).thenReturn(GateMode.REDIS);
        when(redisAdmissionGateway.tryAdmit(1, 1, 101, 30))
                .thenThrow(new RedisAdmissionGateway.ReplicationNotConfirmedException(1, 0));

        AdmissionDecision decision = service.admit(1, 1, 101, "attempt-101");

        assertThat(decision.result()).isEqualTo(AdmissionResult.REJECTED);
        assertThat(decision.gateMode()).isEqualTo(GateMode.REDIS_FAILOVER_PAUSED);
        verify(redisAdmissionGateway, times(1)).tryAdmit(1, 1, 101, 30);
        verify(transactionService, never()).createAdmission(1, 1, 101, "attempt-101", GateMode.REDIS, 1L, 30);
        verify(transactionService).markRedisFailoverPaused(1, 1);
    }

    @Test
    void should_pause_when_redis_timeout_without_waiting_for_replication_retry() {
        BookingJpaRepository repository = mock(BookingJpaRepository.class);
        RedisAdmissionGateway redisAdmissionGateway = mock(RedisAdmissionGateway.class);
        AdmissionTransactionService transactionService = mock(AdmissionTransactionService.class);
        BookingAdmissionService service = new BookingAdmissionService(
                BookingApplicationServiceTest.propertiesWithBulkhead(6, 5, 1, 2),
                repository,
                redisAdmissionGateway,
                transactionService,
                new BookingDbWriteBulkhead(BookingApplicationServiceTest.propertiesWithBulkhead(6, 5, 1, 2)),
                Duration.ofSeconds(1)
        );
        when(repository.gateMode(1, 1)).thenReturn(GateMode.REDIS);
        when(redisAdmissionGateway.tryAdmit(1, 1, 101, 30))
                .thenThrow(new QueryTimeoutException("transient redis timeout"));

        AdmissionDecision decision = service.admit(1, 1, 101, "attempt-101");

        assertThat(decision.result()).isEqualTo(AdmissionResult.REJECTED);
        assertThat(decision.gateMode()).isEqualTo(GateMode.REDIS_FAILOVER_PAUSED);
        verify(redisAdmissionGateway, times(1)).tryAdmit(1, 1, 101, 30);
        verify(redisAdmissionGateway, never()).ping();
        verify(transactionService).markRedisFailoverPaused(1, 1);
    }

    @Test
    void should_open_local_pause_circuit_on_transient_redis_command_timeout() {
        BookingJpaRepository repository = mock(BookingJpaRepository.class);
        RedisAdmissionGateway redisAdmissionGateway = mock(RedisAdmissionGateway.class);
        AdmissionTransactionService transactionService = mock(AdmissionTransactionService.class);
        BookingAdmissionService service = new BookingAdmissionService(
                BookingApplicationServiceTest.propertiesWithBulkhead(6, 5, 1, 2),
                repository,
                redisAdmissionGateway,
                transactionService,
                new BookingDbWriteBulkhead(BookingApplicationServiceTest.propertiesWithBulkhead(6, 5, 1, 2)),
                Duration.ofSeconds(1)
        );
        when(repository.gateMode(1, 1)).thenReturn(GateMode.REDIS);
        when(redisAdmissionGateway.tryAdmit(1, 1, 101, 30))
                .thenThrow(new QueryTimeoutException("transient redis timeout"));

        AdmissionDecision decision = service.admit(1, 1, 101, "attempt-101");

        assertThat(decision.result()).isEqualTo(AdmissionResult.REJECTED);
        assertThat(decision.gateMode()).isEqualTo(GateMode.REDIS_FAILOVER_PAUSED);
        verify(redisAdmissionGateway, times(1)).tryAdmit(1, 1, 101, 30);
        verify(redisAdmissionGateway, never()).ping();
        verify(transactionService).markRedisFailoverPaused(1, 1);
    }

    @Test
    void should_compensate_new_redis_candidate_and_pause_when_mysql_admission_persistence_fails() {
        BookingJpaRepository repository = mock(BookingJpaRepository.class);
        RedisAdmissionGateway redisAdmissionGateway = mock(RedisAdmissionGateway.class);
        AdmissionTransactionService transactionService = mock(AdmissionTransactionService.class);
        BookingProperties properties = BookingApplicationServiceTest.propertiesWithBulkhead(6, 5, 1, 2);
        BookingAdmissionService service = new BookingAdmissionService(
                properties,
                repository,
                redisAdmissionGateway,
                transactionService,
                new BookingDbWriteBulkhead(properties),
                Duration.ofSeconds(1)
        );
        when(repository.gateMode(1, 1)).thenReturn(GateMode.REDIS);
        when(redisAdmissionGateway.tryAdmit(1, 1, 101, 30))
                .thenReturn(new RedisAdmissionGateway.Result(true, 1, true));
        when(transactionService.createAdmission(1, 1, 101, "attempt-101", GateMode.REDIS, 1L, 30))
                .thenThrow(new DataAccessResourceFailureException("mysql unavailable"));

        AdmissionDecision decision = service.admit(1, 1, 101, "attempt-101");

        assertThat(decision.result()).isEqualTo(AdmissionResult.REJECTED);
        assertThat(decision.gateMode()).isEqualTo(GateMode.REDIS_FAILOVER_PAUSED);

        verify(redisAdmissionGateway).tryAdmit(1, 1, 101, 30);
        verify(redisAdmissionGateway).compensateAdmission(1, 1, 101, 1);
        verify(redisAdmissionGateway).pauseAdmission(1, 1, properties.redisFailoverRetryAfter(),
                "ADMISSION_PERSISTENCE_UNAVAILABLE");
        verify(transactionService, times(3))
                .createAdmission(1, 1, 101, "attempt-101", GateMode.REDIS, 1L, 30);
        verify(transactionService, never()).markRedisFailoverPaused(1, 1);
    }

    @Test
    void should_not_compensate_existing_redis_candidate_when_mysql_admission_persistence_fails() {
        BookingJpaRepository repository = mock(BookingJpaRepository.class);
        RedisAdmissionGateway redisAdmissionGateway = mock(RedisAdmissionGateway.class);
        AdmissionTransactionService transactionService = mock(AdmissionTransactionService.class);
        BookingProperties properties = BookingApplicationServiceTest.propertiesWithBulkhead(6, 5, 1, 2);
        BookingAdmissionService service = new BookingAdmissionService(
                properties,
                repository,
                redisAdmissionGateway,
                transactionService,
                new BookingDbWriteBulkhead(properties),
                Duration.ofSeconds(1)
        );
        when(repository.gateMode(1, 1)).thenReturn(GateMode.REDIS);
        when(redisAdmissionGateway.tryAdmit(1, 1, 101, 30))
                .thenReturn(new RedisAdmissionGateway.Result(true, 7, false));
        when(transactionService.createAdmission(1, 1, 101, "attempt-101", GateMode.REDIS, 7L, 30))
                .thenThrow(new DataAccessResourceFailureException("mysql unavailable"));

        AdmissionDecision decision = service.admit(1, 1, 101, "attempt-101");

        assertThat(decision.result()).isEqualTo(AdmissionResult.REJECTED);
        assertThat(decision.gateMode()).isEqualTo(GateMode.REDIS_FAILOVER_PAUSED);
        verify(redisAdmissionGateway, never()).compensateAdmission(1, 1, 101, 7);
        verify(redisAdmissionGateway).pauseAdmission(1, 1, properties.redisFailoverRetryAfter(),
                "ADMISSION_PERSISTENCE_UNAVAILABLE");
    }

    @Test
    void should_reject_cross_replica_admission_pause_without_calling_redis_lua() {
        BookingJpaRepository repository = mock(BookingJpaRepository.class);
        RedisAdmissionGateway redisAdmissionGateway = mock(RedisAdmissionGateway.class);
        AdmissionTransactionService transactionService = mock(AdmissionTransactionService.class);
        BookingProperties properties = BookingApplicationServiceTest.propertiesWithBulkhead(6, 5, 1, 2);
        BookingAdmissionService service = new BookingAdmissionService(
                properties,
                repository,
                redisAdmissionGateway,
                transactionService,
                new BookingDbWriteBulkhead(properties),
                Duration.ofSeconds(1)
        );
        when(repository.gateMode(1, 1)).thenReturn(GateMode.REDIS);
        when(redisAdmissionGateway.isAdmissionPaused(1, 1)).thenReturn(true);

        AdmissionDecision decision = service.admit(1, 1, 101, "attempt-101");

        assertThat(decision.result()).isEqualTo(AdmissionResult.REJECTED);
        assertThat(decision.gateMode()).isEqualTo(GateMode.REDIS_FAILOVER_PAUSED);
        verify(redisAdmissionGateway).isAdmissionPaused(1, 1);
        verify(redisAdmissionGateway, never()).tryAdmit(1, 1, 101, 30);
        verify(repository).gateMode(1, 1);
        verifyNoInteractions(transactionService);
    }

    @Test
    void should_reject_next_request_from_local_pause_circuit_without_calling_redis() {
        BookingJpaRepository repository = mock(BookingJpaRepository.class);
        RedisAdmissionGateway redisAdmissionGateway = mock(RedisAdmissionGateway.class);
        AdmissionTransactionService transactionService = mock(AdmissionTransactionService.class);
        BookingAdmissionService service = new BookingAdmissionService(
                BookingApplicationServiceTest.propertiesWithBulkhead(6, 5, 1, 2),
                repository,
                redisAdmissionGateway,
                transactionService,
                new BookingDbWriteBulkhead(BookingApplicationServiceTest.propertiesWithBulkhead(6, 5, 1, 2)),
                Duration.ofSeconds(1)
        );
        when(repository.gateMode(1, 1)).thenReturn(GateMode.REDIS);
        when(redisAdmissionGateway.tryAdmit(1, 1, 101, 30))
                .thenThrow(new QueryTimeoutException("redis timed out"));

        AdmissionDecision decision = service.admit(1, 1, 101, "attempt-101");
        AdmissionDecision next = service.admit(1, 1, 102, "attempt-102");

        assertThat(decision.result()).isEqualTo(AdmissionResult.REJECTED);
        assertThat(decision.gateMode()).isEqualTo(GateMode.REDIS_FAILOVER_PAUSED);
        assertThat(next.result()).isEqualTo(AdmissionResult.REJECTED);
        assertThat(next.gateMode()).isEqualTo(GateMode.REDIS_FAILOVER_PAUSED);
        verify(redisAdmissionGateway, times(1)).tryAdmit(1, 1, 101, 30);
        verify(redisAdmissionGateway, never()).tryAdmit(1, 1, 102, 30);
        verify(transactionService).markRedisFailoverPaused(1, 1);
    }

    @Test
    void should_return_paused_when_pause_marker_write_is_busy() {
        BookingJpaRepository repository = mock(BookingJpaRepository.class);
        RedisAdmissionGateway redisAdmissionGateway = mock(RedisAdmissionGateway.class);
        AdmissionTransactionService transactionService = mock(AdmissionTransactionService.class);
        BookingAdmissionService service = new BookingAdmissionService(
                BookingApplicationServiceTest.propertiesWithBulkhead(6, 5, 1, 2),
                repository,
                redisAdmissionGateway,
                transactionService,
                new BookingDbWriteBulkhead(BookingApplicationServiceTest.propertiesWithBulkhead(6, 5, 1, 2)),
                Duration.ofSeconds(1)
        );
        when(repository.gateMode(1, 1)).thenReturn(GateMode.REDIS);
        when(redisAdmissionGateway.tryAdmit(1, 1, 101, 30))
                .thenThrow(new QueryTimeoutException("redis timed out"));
        org.mockito.Mockito.doThrow(new BusinessException(BookingErrorCode.SERVICE_BUSY))
                .when(transactionService)
                .markRedisFailoverPaused(1, 1);

        AdmissionDecision decision = service.admit(1, 1, 101, "attempt-101");

        assertThat(decision.result()).isEqualTo(AdmissionResult.REJECTED);
        assertThat(decision.gateMode()).isEqualTo(GateMode.REDIS_FAILOVER_PAUSED);
        verify(transactionService).markRedisFailoverPaused(1, 1);
    }

    @Test
    void should_fast_reject_parallel_request_while_one_request_diagnoses_redis_outage() throws Exception {
        BookingJpaRepository repository = mock(BookingJpaRepository.class);
        RedisAdmissionGateway redisAdmissionGateway = mock(RedisAdmissionGateway.class);
        AdmissionTransactionService transactionService = mock(AdmissionTransactionService.class);
        BookingProperties properties = BookingApplicationServiceTest.propertiesWithBulkhead(
                6, 5, 1, 2, 1, Duration.ofSeconds(2)
        );
        BookingAdmissionService service = new BookingAdmissionService(
                properties,
                repository,
                redisAdmissionGateway,
                transactionService,
                new BookingDbWriteBulkhead(properties),
                Duration.ofSeconds(1)
        );
        CountDownLatch redisCallStarted = new CountDownLatch(1);
        CountDownLatch releaseRedisCall = new CountDownLatch(1);
        when(repository.gateMode(1, 1)).thenReturn(GateMode.REDIS);
        when(redisAdmissionGateway.tryAdmit(1, 1, 101, 30))
                .thenAnswer(ignored -> {
                    redisCallStarted.countDown();
                    releaseRedisCall.await(5, TimeUnit.SECONDS);
                    throw new QueryTimeoutException("redis timed out");
                });

        try (var executor = Executors.newFixedThreadPool(2)) {
            var diagnosing = executor.submit(() -> service.admit(1, 1, 101, "attempt-101"));
            assertThat(redisCallStarted.await(5, TimeUnit.SECONDS)).isTrue();

            AdmissionDecision parallel = service.admit(1, 1, 102, "attempt-102");

            assertThat(parallel.result()).isEqualTo(AdmissionResult.REJECTED);
            assertThat(parallel.gateMode()).isEqualTo(GateMode.REDIS_FAILOVER_PAUSED);

            releaseRedisCall.countDown();
            AdmissionDecision fallback = diagnosing.get(5, TimeUnit.SECONDS);
            assertThat(fallback.result()).isEqualTo(AdmissionResult.REJECTED);
            assertThat(fallback.gateMode()).isEqualTo(GateMode.REDIS_FAILOVER_PAUSED);
            verify(redisAdmissionGateway, never()).tryAdmit(1, 1, 102, 30);
            verify(transactionService).markRedisFailoverPaused(1, 1);
        }
    }
}
