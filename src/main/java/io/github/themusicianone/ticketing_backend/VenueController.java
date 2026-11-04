package io.github.themusicianone.ticketing_backend;


import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.util.Optional;

@RestController
@RequestMapping("/venues")
class VenueController {
    private final VenueService venueService;
    VenueController(VenueService venueService) {
        this.venueService = venueService;
    }

    @GetMapping("/{requestedId}")
    private ResponseEntity<VenueResponse> getVenueById(@PathVariable("requestedId") long requestedId) {
        Optional<VenueResponse> venue = venueService.findById(requestedId);

        if (venue.isPresent()) {
            return ResponseEntity.ok(venue.get());
        }
        return ResponseEntity.notFound().build();
    }


    @PostMapping
    private ResponseEntity<VenueResponse> postVenue(@Valid @RequestBody CreateVenueRequest request, UriComponentsBuilder ucb) {
        VenueResponse created = venueService.create(request);
        URI locationOfNewVenue = ucb.path("/venues/{id}").buildAndExpand(created.id()).toUri();
        return ResponseEntity.created(locationOfNewVenue).body(created);
    }
}
