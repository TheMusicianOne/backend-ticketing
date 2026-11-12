package io.github.themusicianone.ticketing_backend.payment;

import java.util.Locale;

public enum ProviderStatus {
    OPEN, PAID, FAILED, CANCELED, EXPIRED;

    public static ProviderStatus fromProvider(String value) {
        return valueOf(value.toUpperCase(Locale.ROOT));
    }
}