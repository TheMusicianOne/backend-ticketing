package io.github.themusicianone.ticketing_backend.payment;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "app.payments")
public record PaymentProperties(
        String baseUrl,
        String apiKey,
        String webhookUrl,
        String webhookSecret,
        Duration webhookTolerance,
        Duration connectTimeout,
        Duration readTimeout) {
}
