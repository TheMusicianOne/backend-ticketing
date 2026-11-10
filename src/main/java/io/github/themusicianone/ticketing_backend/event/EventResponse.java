package io.github.themusicianone.ticketing_backend.event;

import java.time.Instant;

public record EventResponse(Long id, String name, Instant startsAt, Long venueId) {
    public static EventResponse from(Event event) {
        return new EventResponse(event.getId(), event.getName(), event.getStartsAt(), event.getVenue().getId());
    }
}
