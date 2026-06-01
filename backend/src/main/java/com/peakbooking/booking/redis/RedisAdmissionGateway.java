package com.peakbooking.booking.redis;

import java.time.Duration;
import java.util.List;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
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
                return {'ADMITTED', existing}
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
    private final DefaultRedisScript<List> script;

    public RedisAdmissionGateway(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
        this.script = new DefaultRedisScript<>(LUA, List.class);
    }

    public Result tryAdmit(long productId, long saleEventId, long userId, int candidateLimit) {
        String prefix = "admit:" + productId + ":" + saleEventId;
        List<?> response = redisTemplate.execute(
                script,
                List.of(prefix + ":users", prefix + ":queue", prefix + ":seq"),
                Long.toString(userId),
                Integer.toString(candidateLimit),
                Long.toString(Duration.ofHours(24).toSeconds())
        );
        if (response == null || response.size() != 2) {
            throw new IllegalStateException("Unexpected Redis admission response");
        }
        boolean admitted = "ADMITTED".equals(String.valueOf(response.get(0)));
        long seq = Long.parseLong(String.valueOf(response.get(1)));
        return new Result(admitted, seq);
    }

    public boolean ping() {
        return Boolean.TRUE.equals(redisTemplate.execute((RedisCallback<Boolean>) connection ->
                "PONG".equalsIgnoreCase(connection.ping())
        ));
    }

    public record Result(boolean admitted, long redisSeq) {
    }
}
