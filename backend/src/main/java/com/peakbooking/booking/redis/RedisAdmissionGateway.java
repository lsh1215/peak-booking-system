package com.peakbooking.booking.redis;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.async.BaseRedisAsyncCommands;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.redis.connection.ReturnType;
import org.springframework.stereotype.Component;

@Component
public class RedisAdmissionGateway {

    private static final String LUA = """
            local usersKey = KEYS[1]
            local queueKey = KEYS[2]
            local seqKey = KEYS[3]
            local userId = ARGV[1]
            local candidateLimit = tonumber(ARGV[2])
            local ttlSeconds = tonumber(ARGV[3])

            local existing = redis.call('HGET', usersKey, userId)
            if existing then
                return {'EXISTING', existing}
            end

            local count = redis.call('ZCARD', queueKey)
            if count >= candidateLimit then
                return {'BUSY', '0'}
            end

            local seq = redis.call('INCR', seqKey)
            redis.call('HSET', usersKey, userId, seq)
            redis.call('ZADD', queueKey, seq, userId)
            redis.call('EXPIRE', usersKey, ttlSeconds)
            redis.call('EXPIRE', queueKey, ttlSeconds)
            redis.call('EXPIRE', seqKey, ttlSeconds)
            return {'ADMITTED', tostring(seq)}
            """;

    private final StringRedisTemplate redisTemplate;
    private final int waitReplicas;
    private final long waitTimeoutMillis;

    public RedisAdmissionGateway(
            StringRedisTemplate redisTemplate,
            @Value("${peak-booking.redis-wait-replicas:0}") int waitReplicas,
            @Value("${peak-booking.redis-wait-timeout:0ms}") Duration waitTimeout
    ) {
        if (waitReplicas < 0) {
            throw new IllegalArgumentException("peak-booking.redis-wait-replicas must be greater than or equal to 0");
        }
        if (waitReplicas > 0 && (waitTimeout.isZero() || waitTimeout.isNegative())) {
            throw new IllegalArgumentException("peak-booking.redis-wait-timeout must be positive when Redis WAIT is enabled");
        }
        this.redisTemplate = redisTemplate;
        this.waitReplicas = waitReplicas;
        this.waitTimeoutMillis = waitTimeout.toMillis();
    }

    public Result tryAdmit(long productId, long saleEventId, long userId, int candidateLimit) {
        String prefix = "admit:" + productId + ":" + saleEventId;
        List<?> response = redisTemplate.execute((RedisCallback<List<?>>) connection -> {
            List<?> result = connection.eval(
                    LUA.getBytes(StandardCharsets.UTF_8),
                    ReturnType.MULTI,
                    3,
                    bytes(prefix + ":users"),
                    bytes(prefix + ":queue"),
                    bytes(prefix + ":seq"),
                    bytes(userId),
                    bytes(candidateLimit),
                    bytes(Duration.ofHours(24).toSeconds())
	            );
	            if (newAdmissionWasWritten(result) && waitReplicas > 0) {
	                try {
	                    waitForReplicas(connection);
	                } catch (ReplicationNotConfirmedException replicationFailure) {
	                    throw replicationFailure;
	                } catch (RuntimeException waitFailure) {
                    throw new ReplicationNotConfirmedException(waitReplicas, -1, waitFailure);
                }
            }
            return result;
        });
        if (response == null || response.size() != 2) {
            throw new IllegalStateException("Unexpected Redis admission response");
        }
        String status = stringValue(response.get(0));
        boolean admitted = "ADMITTED".equals(status) || "EXISTING".equals(status);
        long seq = Long.parseLong(stringValue(response.get(1)));
        return new Result(admitted, seq, "ADMITTED".equals(status));
    }

    public boolean ping() {
        return Boolean.TRUE.equals(redisTemplate.execute((RedisCallback<Boolean>) connection ->
                "PONG".equalsIgnoreCase(connection.ping())
        ));
    }

    public boolean probeRecovery() {
        String key = "admit:probe:" + UUID.randomUUID();
        Boolean ready = redisTemplate.execute((RedisCallback<Boolean>) connection -> {
            try {
                connection.execute(
                        "SET",
                        bytes(key),
                        bytes(1),
                        bytes("EX"),
                        bytes(5)
                );
                if (waitReplicas > 0) {
                    waitForReplicas(connection);
                }
                return true;
            } finally {
                connection.del(bytes(key));
            }
        });
        return Boolean.TRUE.equals(ready);
    }

    private boolean newAdmissionWasWritten(List<?> response) {
        return response != null && !response.isEmpty() && "ADMITTED".equals(stringValue(response.get(0)));
    }

    private void waitForReplicas(org.springframework.data.redis.connection.RedisConnection connection) {
        long acknowledgedReplicas = waitForReplication(connection);
        if (acknowledgedReplicas < waitReplicas) {
            throw new ReplicationNotConfirmedException(waitReplicas, acknowledgedReplicas);
        }
    }

    @SuppressWarnings("unchecked")
    private long waitForReplication(org.springframework.data.redis.connection.RedisConnection connection) {
        Object nativeConnection = connection.getNativeConnection();
        BaseRedisAsyncCommands<byte[], byte[]> commands;
        if (nativeConnection instanceof BaseRedisAsyncCommands<?, ?> asyncCommands) {
            commands = (BaseRedisAsyncCommands<byte[], byte[]>) asyncCommands;
        } else if (nativeConnection instanceof StatefulRedisConnection<?, ?> statefulConnection) {
            commands = (BaseRedisAsyncCommands<byte[], byte[]>) statefulConnection.async();
        } else {
            throw new DataAccessResourceFailureException(
                    "Redis WAIT requires Lettuce native async commands but got "
                            + (nativeConnection == null ? "null" : nativeConnection.getClass().getName())
            );
        }

        try {
            Long acknowledged = commands.waitForReplication(waitReplicas, waitTimeoutMillis)
                    .get(waitTimeoutMillis + 100, TimeUnit.MILLISECONDS);
            return acknowledged == null ? 0 : acknowledged;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new ReplicationNotConfirmedException(waitReplicas, -1, exception);
        } catch (ExecutionException | TimeoutException exception) {
            throw new ReplicationNotConfirmedException(waitReplicas, -1, exception);
        }
    }

    private byte[] bytes(Object value) {
        return String.valueOf(value).getBytes(StandardCharsets.UTF_8);
    }

    private String stringValue(Object value) {
        if (value instanceof byte[] bytes) {
            return new String(bytes, StandardCharsets.UTF_8);
        }
        return String.valueOf(value);
    }

    public record Result(boolean admitted, long redisSeq, boolean newlyCreated) {

        public Result(boolean admitted, long redisSeq) {
            this(admitted, redisSeq, false);
        }
    }

    public static final class ReplicationNotConfirmedException extends DataAccessResourceFailureException {

        public ReplicationNotConfirmedException(long requiredReplicas, long acknowledgedReplicas) {
            super(message(requiredReplicas, acknowledgedReplicas));
        }

        public ReplicationNotConfirmedException(
                long requiredReplicas,
                long acknowledgedReplicas,
                Throwable cause
        ) {
            super(message(requiredReplicas, acknowledgedReplicas), cause);
        }

        private static String message(long requiredReplicas, long acknowledgedReplicas) {
            return "Redis admission was not replicated to enough replicas: required=%d acknowledged=%d"
                    .formatted(requiredReplicas, acknowledgedReplicas);
        }
    }
}
