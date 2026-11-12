package io.github.themusicianone.ticketing_backend.payment;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
@ConditionalOnProperty(name = "app.payments.provider", havingValue = "http")
class HttpPaymentGateway implements PaymentGateway {

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
    public CreatedPayment createPayment(PaymentRequest request) {
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
}