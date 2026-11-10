package io.github.themusicianone.ticketing_backend;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

import java.util.List;

public record GenerateSeatRequest(
        @NotEmpty @Valid  List<SectionLayout> sections
){

}