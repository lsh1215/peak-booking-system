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
            local activeLimit = tonumber(ARGV[2])
            local ttlSeconds = tonumber(ARGV[3])

            local existing = redis.call('HGET', usersKey, userId)
            if existing then
                local rank = redis.call('ZRANK', queueKey, userId)
                if not rank then
                    return {'REJECTED', '0', '0'}
                end
                local candidateRank = rank + 1
                if candidateRank <= activeLimit then
                    return {'ACTIVE_EXISTING', existing, tostring(candidateRank)}
                end
                return {'WAITING_EXISTING', existing, tostring(candidateRank)}
            end

            local seq = redis.call('INCR', seqKey)
            redis.call('HSET', usersKey, userId, seq)
            redis.call('ZADD', queueKey, seq, userId)
            redis.call('EXPIRE', usersKey, ttlSeconds)
            redis.call('EXPIRE', queueKey, ttlSeconds)
            redis.call('EXPIRE', seqKey, ttlSeconds)
            local rank = redis.call('ZRANK', queueKey, userId)
            if not rank then
                return {'REJECTED', '0', '0'}
            end
            local candidateRank = rank + 1
            if candidateRank <= activeLimit then
                return {'ACTIVE_NEW', tostring(seq), tostring(candidateRank)}
            end
            return {'WAITING_NEW', tostring(seq), tostring(candidateRank)}
            """;
    private static final String COMPENSATE_LUA = """
            local usersKey = KEYS[1]
            local queueKey = KEYS[2]
            local userId = ARGV[1]
            local redisSeq = ARGV[2]

            local existing = redis.call('HGET', usersKey, userId)
            if existing == redisSeq then
                redis.call('HDEL', usersKey, userId)
                redis.call('ZREM', queueKey, userId)
                return 1
            end
            return 0
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
        String prefix = admissionPrefix(productId, saleEventId);
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
        if (response == null || response.size() != 3) {
            throw new IllegalStateException("Unexpected Redis admission response");
        }
        String status = stringValue(response.get(0));
        boolean admitted = "ACTIVE_NEW".equals(status) || "ACTIVE_EXISTING".equals(status);
        long seq = Long.parseLong(stringValue(response.get(1)));
        int candidateRank = Integer.parseInt(stringValue(response.get(2)));
        return new Result(admitted, seq, "ACTIVE_NEW".equals(status) || "WAITING_NEW".equals(status), candidateRank);
    }

    public boolean isAdmissionPaused(long productId, long saleEventId) {
        return Boolean.TRUE.equals(redisTemplate.hasKey(pauseKey(productId, saleEventId)));
    }

    public void pauseAdmission(long productId, long saleEventId, Duration ttl, String reason) {
        Duration effectiveTtl = ttl.isZero() || ttl.isNegative() ? Duration.ofSeconds(1) : ttl;
        redisTemplate.opsForValue().set(pauseKey(productId, saleEventId), reason, effectiveTtl);
    }

    public boolean compensateAdmission(long productId, long saleEventId, long userId, long redisSeq) {
        return releaseAdmission(productId, saleEventId, userId, redisSeq);
    }

    public boolean releaseAdmission(long productId, long saleEventId, long userId, long redisSeq) {
        String prefix = admissionPrefix(productId, saleEventId);
        Long result = redisTemplate.execute((RedisCallback<Long>) connection -> {
            Object value = connection.eval(
                    COMPENSATE_LUA.getBytes(StandardCharsets.UTF_8),
                    ReturnType.INTEGER,
                    2,
                    bytes(prefix + ":users"),
                    bytes(prefix + ":queue"),
                    bytes(userId),
                    bytes(redisSeq)
            );
            if (value instanceof Number number) {
                return number.longValue();
            }
            return 0L;
        });
        return result != null && result == 1L;
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
        if (response == null || response.isEmpty()) {
            return false;
        }
        String status = stringValue(response.get(0));
        return "ACTIVE_NEW".equals(status) || "WAITING_NEW".equals(status);
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

    private String admissionPrefix(long productId, long saleEventId) {
        return "admit:" + productId + ":" + saleEventId;
    }

    private String pauseKey(long productId, long saleEventId) {
        return admissionPrefix(productId, saleEventId) + ":pause";
    }

    private String stringValue(Object value) {
        if (value instanceof byte[] bytes) {
            return new String(bytes, StandardCharsets.UTF_8);
        }
        return String.valueOf(value);
    }

    public record Result(boolean admitted, long redisSeq, boolean newlyCreated, int candidateRank) {

        public Result(boolean admitted, long redisSeq) {
            this(admitted, redisSeq, false, admitted ? Math.toIntExact(redisSeq) : 0);
        }

        public Result(boolean admitted, long redisSeq, boolean newlyCreated) {
            this(admitted, redisSeq, newlyCreated, admitted ? Math.toIntExact(redisSeq) : 0);
        }

        public static Result waiting(long redisSeq, boolean newlyCreated, int candidateRank) {
            return new Result(false, redisSeq, newlyCreated, candidateRank);
        }

        public boolean waitingRoom() {
            return !admitted && redisSeq > 0 && candidateRank > 0;
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
