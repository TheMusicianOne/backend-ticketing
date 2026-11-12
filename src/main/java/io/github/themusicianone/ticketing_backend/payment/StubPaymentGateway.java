package io.github.themusicianone.ticketing_backend.payment;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "app.payments.provider", havingValue = "stub", matchIfMissing = true)
class StubPaymentGateway implements PaymentGateway {

    @Override
    public CreatedPayment createPayment(PaymentRequest request) {
        String paymentId = "stub_" + request.idempotencyKey();
        return new CreatedPayment(paymentId, "https://pay.example.test/checkout/" + paymentId);
    }
}
