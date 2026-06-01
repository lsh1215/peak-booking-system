package com.peakbooking.booking.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.peakbooking.booking.domain.PaymentPlanLine;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class CanonicalRequestHashCalculator {

    private final ObjectMapper objectMapper;

    public CanonicalRequestHashCalculator(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper.copy().findAndRegisterModules();
    }

    public String hash(BookingCommand command, String bookingAttemptId) {
        List<Map<String, Object>> methods = command.paymentPlan().orderedLines().stream()
                .map(this::methodLine)
                .toList();
        Map<String, Object> canonical = Map.of(
                "sale_event_id", command.saleEventId(),
                "product_id", command.productId(),
                "user_id", command.userId(),
                "booking_attempt_id", bookingAttemptId,
                "payment_methods", methods,
                "point_amount", command.paymentPlan().amountOf(com.peakbooking.booking.domain.PaymentMethodType.Y_POINT),
                "pg_amount", command.paymentPlan().totalAmount()
                        - command.paymentPlan().amountOf(com.peakbooking.booking.domain.PaymentMethodType.Y_POINT),
                "total_amount", command.totalAmount(),
                "currency", command.currency(),
                "payment_policy_version", command.paymentPolicyVersion()
        );
        try {
            byte[] json = objectMapper.writeValueAsBytes(canonical);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to canonicalize booking request", e);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to hash booking request", e);
        }
    }

    private Map<String, Object> methodLine(PaymentPlanLine line) {
        return Map.of("type", line.type().name(), "amount", line.amount());
    }
}
