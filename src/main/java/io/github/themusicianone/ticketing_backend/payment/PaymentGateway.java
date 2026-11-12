package io.github.themusicianone.ticketing_backend.payment;

public interface PaymentGateway {
    CreatedPayment createPayment (PaymentRequest request);
    boolean cancel(String paymentId);
    ProviderStatus getStatus(String paymentId);
    record PaymentRequest(String idempotencyKey, int amountCents, String description){}

    record CreatedPayment(String paymentId, String checkoutUrl){}
}
