package io.github.themusicianone.ticketing_backend;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;

@Service
public class EventService {
    private final EventRepository eventRepository;
    private final VenueRepository venueRepository;

    EventService(EventRepository eventRepository, VenueRepository venueRepository) {
        this.eventRepository = eventRepository;
        this.venueRepository = venueRepository;
    }

    Optional<EventResponse> findById(Long id) {
        Optional<Event> event = eventRepository.findById(id);
        if (event.isPresent()) {
            return Optional.of(EventResponse.from(event.get()));
        }
        return Optional.empty();
    }

    @Transactional
    EventResponse create(CreateEventRequest request) {

        Venue venue = venueRepository.findById(request.venueId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Venue not found"));
        Event event = new Event(request.name(), request.startsAt(), venue);
        Event savedEvent = eventRepository.save(event);
        return EventResponse.from(savedEvent);

    }
}
