package io.github.themusicianone.ticketing_backend.payment;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
@ConditionalOnProperty(name = "app.payments.provider", havingValue = "stub", matchIfMissing = true)
public class StubPaymentGateway implements PaymentGateway {

    @Override
    public CreatedPayment createPayment(PaymentRequest request) {
        String paymentId = "stub_" + request.idempotencyKey();
        return new CreatedPayment(paymentId, "https://pay.example.test/checkout/" + paymentId);
    }
    private final Map<String, ProviderStatus> statuses = new ConcurrentHashMap<>();

    @Override
    public ProviderStatus getStatus(String paymentId) {
        return statuses.getOrDefault(paymentId, ProviderStatus.OPEN);
    }

    @Override
    public boolean cancel(String paymentId) {
        return statuses.putIfAbsent(paymentId, ProviderStatus.CANCELED) == null
                || statuses.replace(paymentId, ProviderStatus.OPEN, ProviderStatus.CANCELED);
    }

    /** For tests: pretend the customer finished paying at the provider. */
    public void setStatus(String paymentId, ProviderStatus status) {
        statuses.put(paymentId, status);
    }

    @Override
    public void refund(String paymentId, String idempotencyKey){
        statuses.put(paymentId, ProviderStatus.REFUNDED);
    }
}
