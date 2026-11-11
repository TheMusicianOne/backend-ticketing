package io.github.themusicianone.ticketing_backend.hold;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

public record CreateHoldRequest(
        @NotEmpty @Size(max = 6) List<@NotNull Long> seatIds
) {
}
