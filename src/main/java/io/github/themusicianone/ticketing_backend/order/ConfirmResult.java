package io.github.themusicianone.ticketing_backend.order;

public enum ConfirmResult {
    CONFIRMED,          // this call confirmed it
    ALREADY_CONFIRMED,  // a webhook or the job already did, so nothing to do
    NEEDS_REFUND        // paid, but the order had already failed or expired
}
