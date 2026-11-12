package io.github.themusicianone.ticketing_backend.payment;

import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;

@Component
class WebHookSignatureVerifier {

    private final PaymentProperties properties;

    WebHookSignatureVerifier(PaymentProperties properties) {
        this.properties = properties;
    }

    /** Returns true only if the signature is valid and recent. */
    boolean isValid(String header, byte[] body, Instant now) {
        if (header == null) {
            return false;
        }

        Map<String, String> parts = new HashMap<>();
        for (String part : header.split(",")) {
            String[] keyValue = part.split("=", 2);
            if (keyValue.length == 2) {
                parts.put(keyValue[0].trim(), keyValue[1].trim());
            }
        }

        String timestamp = parts.get("t");
        String signature = parts.get("v1");
        if (timestamp == null || signature == null) {
            return false;
        }

        long seconds;
        try {
            seconds = Long.parseLong(timestamp);
        } catch (NumberFormatException e) {
            return false;
        }
        Duration age = Duration.between(Instant.ofEpochSecond(seconds), now).abs();
        if (age.compareTo(properties.webhookTolerance()) > 0) {
            return false;
        }

        byte[] expected = hmac(timestamp + ".", body);
        byte[] actual;
        try {
            actual = HexFormat.of().parseHex(signature);
        } catch (IllegalArgumentException e) {
            return false;
        }
        return MessageDigest.isEqual(expected, actual);
    }

    private byte[] hmac(String prefix, byte[] body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(
                    properties.webhookSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            mac.update(prefix.getBytes(StandardCharsets.UTF_8));
            mac.update(body);
            return mac.doFinal();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HMAC-SHA256 is not available", e);
        }
    }
}