package io.github.themusicianone.ticketing_backend.payment;

public class PaymentNotFoundException extends RuntimeException {

    public PaymentNotFoundException(String paymentId) {
        super("Payment provider does not know payment " + paymentId);
    }
}
