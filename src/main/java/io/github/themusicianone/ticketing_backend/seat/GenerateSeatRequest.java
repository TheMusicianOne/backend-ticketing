package io.github.themusicianone.ticketing_backend.seat;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

import java.util.List;

public record GenerateSeatRequest(
        @NotEmpty @Valid  List<SectionLayout> sections
){

}