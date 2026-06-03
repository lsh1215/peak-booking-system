package com.peakbooking.booking.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import io.lettuce.core.RedisFuture;
import io.lettuce.core.api.async.BaseRedisAsyncCommands;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.ReturnType;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;

class RedisAdmissionGatewayTest {

    @Test
    void should_wait_for_replicas_after_new_admission_write() throws Exception {
        RedisConnection connection = mock(RedisConnection.class);
        BaseRedisAsyncCommands<byte[], byte[]> commands = nativeCommands(connection, 1L);
        StringRedisTemplate redisTemplate = redisTemplate(connection);
        when(connection.eval(any(byte[].class), eq(ReturnType.MULTI), eq(3), any(byte[][].class)))
                .thenReturn(List.of(bytes("ADMITTED"), bytes("7")));
        RedisAdmissionGateway gateway = new RedisAdmissionGateway(redisTemplate, 1, Duration.ofMillis(100));

        RedisAdmissionGateway.Result result = gateway.tryAdmit(1, 1, 101, 30);

        assertThat(result.admitted()).isTrue();
        assertThat(result.redisSeq()).isEqualTo(7);
        assertThat(result.newlyCreated()).isTrue();
        verify(commands).waitForReplication(1, 100);
    }

    @Test
    void should_fail_when_new_admission_is_not_replicated_to_enough_replicas() throws Exception {
        RedisConnection connection = mock(RedisConnection.class);
        nativeCommands(connection, 0L);
        StringRedisTemplate redisTemplate = redisTemplate(connection);
        when(connection.eval(any(byte[].class), eq(ReturnType.MULTI), eq(3), any(byte[][].class)))
                .thenReturn(List.of(bytes("ADMITTED"), bytes("7")));
        RedisAdmissionGateway gateway = new RedisAdmissionGateway(redisTemplate, 1, Duration.ofMillis(100));

        assertThatThrownBy(() -> gateway.tryAdmit(1, 1, 101, 30))
                .isInstanceOf(DataAccessResourceFailureException.class)
                .hasMessageContaining("required=1 acknowledged=0");
    }

    @Test
    void should_tag_wait_timeout_as_replication_not_confirmed() throws Exception {
        RedisConnection connection = mock(RedisConnection.class);
        nativeCommandsFailure(connection, new QueryTimeoutException("wait timed out"));
        StringRedisTemplate redisTemplate = redisTemplate(connection);
        when(connection.eval(any(byte[].class), eq(ReturnType.MULTI), eq(3), any(byte[][].class)))
                .thenReturn(List.of(bytes("ADMITTED"), bytes("7")));
        RedisAdmissionGateway gateway = new RedisAdmissionGateway(redisTemplate, 1, Duration.ofMillis(50));

        assertThatThrownBy(() -> gateway.tryAdmit(1, 1, 101, 30))
                .isInstanceOf(RedisAdmissionGateway.ReplicationNotConfirmedException.class)
                .hasMessageContaining("required=1 acknowledged=-1")
                .hasCauseInstanceOf(QueryTimeoutException.class);
    }

    @Test
    void should_not_wait_when_existing_admission_is_returned_without_new_write() {
        RedisConnection connection = mock(RedisConnection.class);
        BaseRedisAsyncCommands<byte[], byte[]> commands = mock(BaseRedisAsyncCommands.class);
        when(connection.getNativeConnection()).thenReturn(commands);
        StringRedisTemplate redisTemplate = redisTemplate(connection);
        when(connection.eval(any(byte[].class), eq(ReturnType.MULTI), eq(3), any(byte[][].class)))
                .thenReturn(List.of(bytes("EXISTING"), bytes("7")));
        RedisAdmissionGateway gateway = new RedisAdmissionGateway(redisTemplate, 1, Duration.ofMillis(100));

        RedisAdmissionGateway.Result result = gateway.tryAdmit(1, 1, 101, 30);

        assertThat(result.admitted()).isTrue();
        assertThat(result.redisSeq()).isEqualTo(7);
        assertThat(result.newlyCreated()).isFalse();
        verify(commands, never()).waitForReplication(any(Integer.class), any(Long.class));
    }

    @Test
    void should_probe_recovery_with_write_and_replication_wait() throws Exception {
        RedisConnection connection = mock(RedisConnection.class);
        BaseRedisAsyncCommands<byte[], byte[]> commands = nativeCommands(connection, 1L);
        StringRedisTemplate redisTemplate = redisTemplate(connection);
        RedisAdmissionGateway gateway = new RedisAdmissionGateway(redisTemplate, 1, Duration.ofMillis(100));

        boolean ready = gateway.probeRecovery();

        assertThat(ready).isTrue();
        verify(connection).execute(eq("SET"), any(byte[][].class));
        verify(commands).waitForReplication(1, 100);
        verify(connection).del(any(byte[].class));
    }

    @Test
    void should_fail_probe_when_recovery_write_is_not_replicated() throws Exception {
        RedisConnection connection = mock(RedisConnection.class);
        nativeCommands(connection, 0L);
        StringRedisTemplate redisTemplate = redisTemplate(connection);
        RedisAdmissionGateway gateway = new RedisAdmissionGateway(redisTemplate, 1, Duration.ofMillis(100));

        assertThatThrownBy(gateway::probeRecovery)
                .isInstanceOf(RedisAdmissionGateway.ReplicationNotConfirmedException.class)
                .hasMessageContaining("required=1 acknowledged=0");

        verify(connection).del(any(byte[].class));
    }

    @Test
    void should_reject_non_positive_wait_timeout_when_wait_is_enabled() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);

        assertThatThrownBy(() -> new RedisAdmissionGateway(redisTemplate, 1, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("redis-wait-timeout must be positive");
    }

    @Test
    void should_reject_negative_wait_replicas() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);

        assertThatThrownBy(() -> new RedisAdmissionGateway(redisTemplate, -1, Duration.ofMillis(50)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("redis-wait-replicas");
    }

    private StringRedisTemplate redisTemplate(RedisConnection connection) {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        when(redisTemplate.execute(any(RedisCallback.class))).thenAnswer(invocation -> {
            RedisCallback<?> callback = invocation.getArgument(0);
            return callback.doInRedis(connection);
        });
        return redisTemplate;
    }

    @SuppressWarnings("unchecked")
    private BaseRedisAsyncCommands<byte[], byte[]> nativeCommands(RedisConnection connection, long acknowledged)
            throws Exception {
        BaseRedisAsyncCommands<byte[], byte[]> commands = mock(BaseRedisAsyncCommands.class);
        RedisFuture<Long> future = mock(RedisFuture.class);
        when(future.get(any(Long.class), eq(TimeUnit.MILLISECONDS))).thenReturn(acknowledged);
        when(commands.waitForReplication(any(Integer.class), any(Long.class))).thenReturn(future);
        when(connection.getNativeConnection()).thenReturn(commands);
        return commands;
    }

    @SuppressWarnings("unchecked")
    private void nativeCommandsFailure(RedisConnection connection, RuntimeException failure) throws Exception {
        BaseRedisAsyncCommands<byte[], byte[]> commands = mock(BaseRedisAsyncCommands.class);
        RedisFuture<Long> future = mock(RedisFuture.class);
        when(future.get(any(Long.class), eq(TimeUnit.MILLISECONDS))).thenThrow(failure);
        when(commands.waitForReplication(any(Integer.class), any(Long.class))).thenReturn(future);
        when(connection.getNativeConnection()).thenReturn(commands);
    }

    private byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
