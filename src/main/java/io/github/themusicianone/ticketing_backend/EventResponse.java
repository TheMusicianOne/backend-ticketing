package io.github.themusicianone.ticketing_backend;

import java.time.Instant;

record EventResponse(Long id, String name, Instant startsAt) {
    static EventResponse from(Event event) {
        return new EventResponse(event.getId(), event.getName(), event.getStartsAt());
    }
}
