package io.github.themusicianone.ticketing_backend.payment;

public interface PaymentGateway {
    CreatedPayment createPayment (PaymentRequest request);
    record PaymentRequest(String idempotencyKey, int amountCents, String description){}

    record CreatedPayment(String paymentId, String checkoutUrl){}
}
