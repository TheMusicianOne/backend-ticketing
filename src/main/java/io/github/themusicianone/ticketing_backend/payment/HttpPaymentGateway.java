package io.github.themusicianone.ticketing_backend.payment;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.resilience.annotation.Retryable;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.io.IOException;

@Component
@ConditionalOnProperty(name = "app.payments.provider", havingValue = "http")
class HttpPaymentGateway implements PaymentGateway {
    private static final Logger log = LoggerFactory.getLogger(HttpPaymentGateway.class);
    private final RestClient restClient;
    private final PaymentProperties properties;

    HttpPaymentGateway(RestClient.Builder builder, PaymentProperties properties) {
        this.properties = properties;

        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(properties.connectTimeout());
        requestFactory.setReadTimeout(properties.readTimeout());

        this.restClient = builder
                .baseUrl(properties.baseUrl())
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + properties.apiKey())
                .requestFactory(requestFactory)
                .build();
    }

    @Override
    @Retryable(
            includes = {ResourceAccessException.class, HttpServerErrorException.class, IOException.class},
            maxRetries =2,
            delay=200,
            multiplier = 2.0,
            jitter = 50,
            maxDelay = 2000

    )
    public CreatedPayment createPayment(PaymentRequest request) {
        //log.info("Calling payment provider for {}", request.idempotencyKey());
        ProviderPayment payment = restClient.post()
                .uri("/v1/payments")
                .header("Idempotency-Key", request.idempotencyKey())
                .contentType(MediaType.APPLICATION_JSON)
                .body(new CreatePaymentBody(
                        request.amountCents(), "EUR", request.description(), properties.webhookUrl()))
                .retrieve()
                .body(ProviderPayment.class);

        return new CreatedPayment(payment.id(), payment.checkoutUrl());
    }

    // What we send, matching the provider's camelCase JSON
    record CreatePaymentBody(int amountCents, String currency, String description, String webhookUrl) {
    }

    // The parts of the provider's answer we need. Spring ignores the other fields.
    record ProviderPayment(String id, String status, String checkoutUrl) {
    }

    @Override
    public ProviderStatus getStatus(String paymentId) {
        try {
            ProviderPayment payment = restClient.get()
                    .uri("/v1/payments/{id}", paymentId)
                    .retrieve()
                    .body(ProviderPayment.class);
            return ProviderStatus.fromProvider(payment.status());
        } catch (HttpClientErrorException.NotFound e) {
            throw new PaymentNotFoundException(paymentId);
        }
    }

    @Override
    public boolean cancel(String paymentId) {
        try {
            restClient.post()
                    .uri("/v1/payments/{id}/cancel", paymentId)
                    .retrieve()
                    .toBodilessEntity();
            return true;
        } catch (HttpClientErrorException.Conflict e) {
            return false;
        }
    }
}