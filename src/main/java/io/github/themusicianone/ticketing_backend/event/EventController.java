package io.github.themusicianone.ticketing_backend.event;


import io.github.themusicianone.ticketing_backend.common.PageResponse;
import jakarta.validation.Valid;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.util.Optional;

@RestController
@RequestMapping("/events")
class EventController {
    private final EventService eventService;

    private EventController(EventService eventService) {
        this.eventService = eventService;
    }

    @GetMapping("/{requestedId}")
    private ResponseEntity<EventResponse> getEvent(@PathVariable("requestedId") long requestedId) {
        Optional<EventResponse> event = eventService.findById(requestedId);

        if (event.isPresent()) {
            return ResponseEntity.ok(event.get());
        }
        return ResponseEntity.notFound().build();
    }

    @PostMapping
    private ResponseEntity<EventResponse> postEvent(@Valid @RequestBody CreateEventRequest newCreateEventRequest, UriComponentsBuilder ucb) {

        EventResponse created = eventService.create(newCreateEventRequest);
        URI locationOfNewEvent = ucb.path("/events/{id}").buildAndExpand(created.id()).toUri();
        return ResponseEntity.created(locationOfNewEvent).body(created);
    }

    @GetMapping
    PageResponse<EventResponse> getEvents(
            @PageableDefault(size = 20, sort = "id") Pageable pageable) {
        return eventService.findAll(pageable);
    }

}
