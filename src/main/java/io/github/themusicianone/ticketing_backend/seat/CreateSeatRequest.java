package io.github.themusicianone.ticketing_backend.seat;

import jakarta.validation.constraints.*;

public record CreateSeatRequest( @NotBlank @Size(max = 20) String section,
                                 @NotBlank @Size(max=20) String rowLabel,
                                 @NotNull @Positive Integer seatNumber,
                                 @NotNull @PositiveOrZero Integer priceCents)  {
}
