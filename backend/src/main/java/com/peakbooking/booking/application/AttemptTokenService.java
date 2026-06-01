package com.peakbooking.booking.application;

import com.peakbooking.booking.config.BookingProperties;
import com.peakbooking.booking.domain.BookingErrorCode;
import com.peakbooking.common.exception.BusinessException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

@Component
public class AttemptTokenService {

    private static final String HMAC_ALGORITHM = "HmacSHA256";

    private final BookingProperties properties;
    private final Clock clock;

    public AttemptTokenService(BookingProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
    }

    public AttemptToken issue(long userId, long saleEventId, long productId) {
        String attemptId = "ba_" + UUID.randomUUID();
        String payload = String.join(":",
                attemptId,
                Long.toString(userId),
                Long.toString(saleEventId),
                Long.toString(productId),
                Long.toString(Instant.now(clock).getEpochSecond())
        );
        String encodedPayload = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(payload.getBytes(StandardCharsets.UTF_8));
        String signature = sign(encodedPayload);
        return new AttemptToken(encodedPayload + "." + signature, attemptId, userId, saleEventId, productId);
    }

    public AttemptToken verify(String rawToken, long expectedUserId, long expectedSaleEventId, long expectedProductId) {
        String[] parts = rawToken == null ? new String[0] : rawToken.split("\\.", -1);
        if (parts.length != 2 || !constantTimeEquals(sign(parts[0]), parts[1])) {
            throw new BusinessException(BookingErrorCode.INVALID_ATTEMPT_TOKEN);
        }
        String payload = new String(Base64.getUrlDecoder().decode(parts[0]), StandardCharsets.UTF_8);
        String[] fields = payload.split(":", -1);
        if (fields.length != 5) {
            throw new BusinessException(BookingErrorCode.INVALID_ATTEMPT_TOKEN);
        }
        String attemptId = fields[0];
        long userId = parseLong(fields[1]);
        long saleEventId = parseLong(fields[2]);
        long productId = parseLong(fields[3]);
        if (userId != expectedUserId || saleEventId != expectedSaleEventId || productId != expectedProductId) {
            throw new BusinessException(BookingErrorCode.INVALID_ATTEMPT_TOKEN);
        }
        return new AttemptToken(rawToken, attemptId, userId, saleEventId, productId);
    }

    private long parseLong(String value) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            throw new BusinessException(BookingErrorCode.INVALID_ATTEMPT_TOKEN);
        }
    }

    private String sign(String encodedPayload) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(
                    properties.attemptTokenSecret().getBytes(StandardCharsets.UTF_8),
                    HMAC_ALGORITHM
            ));
            return HexFormat.of().formatHex(mac.doFinal(encodedPayload.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to sign booking attempt token", e);
        }
    }

    private boolean constantTimeEquals(String expected, String actual) {
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                actual.getBytes(StandardCharsets.UTF_8)
        );
    }
}
