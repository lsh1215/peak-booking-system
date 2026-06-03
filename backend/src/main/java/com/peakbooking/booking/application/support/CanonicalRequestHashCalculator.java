package com.peakbooking.booking.application.support;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.peakbooking.booking.application.dto.BookingCommand;
import com.peakbooking.booking.domain.PaymentPlanLine;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class CanonicalRequestHashCalculator {

    private final ObjectMapper objectMapper;

    public CanonicalRequestHashCalculator(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper.copy()
                .findAndRegisterModules()
                .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
    }

    public String hash(BookingCommand command, String bookingAttemptId) {
        List<Map<String, Object>> methods = command.paymentPlan().orderedLines().stream()
                .map(this::methodLine)
                .toList();
        Map<String, Object> canonical = new LinkedHashMap<>();
        canonical.put("sale_event_id", command.saleEventId());
        canonical.put("product_id", command.productId());
        canonical.put("user_id", command.userId());
        canonical.put("booking_attempt_id", bookingAttemptId);
        canonical.put("payment_methods", methods);
        canonical.put("point_amount", command.paymentPlan().amountOf(
                com.peakbooking.booking.domain.PaymentMethodType.Y_POINT));
        canonical.put("pg_amount", command.paymentPlan().totalAmount()
                - command.paymentPlan().amountOf(com.peakbooking.booking.domain.PaymentMethodType.Y_POINT));
        canonical.put("total_amount", command.totalAmount());
        canonical.put("currency", command.currency());
        canonical.put("payment_policy_version", command.paymentPolicyVersion());
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
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("type", line.type().name());
        value.put("amount", line.amount());
        return value;
    }
}
