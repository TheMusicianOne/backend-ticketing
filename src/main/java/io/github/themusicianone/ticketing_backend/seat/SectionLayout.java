package io.github.themusicianone.ticketing_backend.seat;

import jakarta.validation.constraints.*;

public record SectionLayout(
        @NotBlank @Size(max = 20) String name,
        @NotNull @Positive @Max(100) Integer rows,
        @NotNull @Positive @Max(100) Integer seatsPerRow,
        @NotNull @PositiveOrZero Integer priceCents
) {
}