package io.github.themusicianone.ticketing_backend;


import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.util.Optional;

@RestController
@RequestMapping("/events/{eventId}/seats")
public class SeatController {
    private final SeatService seatService;
    public SeatController(SeatService seatService) {
        this.seatService = seatService;
    }

    @GetMapping("/{seatId}")
    private ResponseEntity<SeatResponse> getSeat(@PathVariable Long eventId,@PathVariable Long seatId) {
        Optional<SeatResponse> seat = seatService.findById(eventId, seatId);

        if (seat.isPresent()) {
            return ResponseEntity.ok(seat.get());
        }
        return ResponseEntity.notFound().build();

    }

    @PostMapping
    private ResponseEntity<SeatResponse> postSeat(@PathVariable Long eventId, @Valid @RequestBody CreateSeatRequest request, UriComponentsBuilder ucb) {
        SeatResponse created = seatService.create(eventId, request);

        URI locationOfNewSeat = ucb.path("/events/{eventId}/seats/{seatId}").buildAndExpand(created.eventId(),created.id()).toUri();
        return ResponseEntity.created(locationOfNewSeat).body(created);
    }

    @PostMapping("/generate")
    private ResponseEntity<GenerateSeatResponse> generateSeats(@PathVariable Long eventId,  @Valid @RequestBody GenerateSeatRequest request) {
    GenerateSeatResponse result = seatService.generate(eventId, request);
    return ResponseEntity.status(HttpStatus.CREATED).body(result);

    }
}
