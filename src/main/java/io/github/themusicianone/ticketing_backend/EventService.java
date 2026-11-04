package io.github.themusicianone.ticketing_backend;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Service
public class EventService {
    private final EventRepository eventRepository;
    EventService(EventRepository eventRepository) {
        this.eventRepository = eventRepository;
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

        Event event = new Event(request.name(), request.startsAt());
        Event savedEvent = eventRepository.save(event);
        return EventResponse.from(savedEvent);

    }
}
