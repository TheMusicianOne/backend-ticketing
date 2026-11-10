package io.github.themusicianone.ticketing_backend.venue;


import jakarta.validation.constraints.NotBlank;

public record CreateVenueRequest(@NotBlank String name, @NotBlank String city){}


