package com.peakbooking.booking.redis;

import io.lettuce.core.ClientOptions;
import io.lettuce.core.SocketOptions;
import io.lettuce.core.TimeoutOptions;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.data.redis.LettuceClientConfigurationBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RedisClientFailFastConfiguration {

    @Bean
    LettuceClientConfigurationBuilderCustomizer redisAdmissionFailFastCustomizer(
            @Value("${spring.data.redis.timeout:500ms}") Duration redisTimeout
    ) {
        Duration timeout = positive(redisTimeout);
        return builder -> builder
                .commandTimeout(timeout)
                .clientOptions(ClientOptions.builder()
                        .autoReconnect(true)
                        .disconnectedBehavior(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS)
                        .requestQueueSize(128)
                        .socketOptions(SocketOptions.builder()
                                .connectTimeout(timeout)
                                .tcpNoDelay(true)
                                .build())
                        .timeoutOptions(TimeoutOptions.builder()
                                .timeoutCommands(true)
                                .fixedTimeout(timeout)
                                .build())
                        .build());
    }

    private Duration positive(Duration timeout) {
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            return Duration.ofMillis(100);
        }
        return timeout;
    }
}
