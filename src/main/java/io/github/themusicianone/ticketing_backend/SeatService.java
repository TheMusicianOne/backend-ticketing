package io.github.themusicianone.ticketing_backend;



import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;

@Service
public class SeatService {

    private final SeatRepository seatRepository;
    private final EventRepository eventRepository;

    public SeatService(SeatRepository seatRepository, EventRepository eventRepository) {
        this.seatRepository = seatRepository;
        this.eventRepository = eventRepository;
    }

    Optional<SeatResponse> findById(Long eventId, Long id){
        Optional<Seat> seat = seatRepository.findByIdAndEvent_Id(id, eventId);

        if (seat.isPresent()){
            return Optional.of(SeatResponse.from(seat.get()));
        }
        return Optional.empty();
    }

    @Transactional
    SeatResponse create(Long eventId, CreateSeatRequest request){
        Event event = eventRepository.findById(eventId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,"Event not found"));
        Seat seat = new Seat(event, request.section(), request.rowLabel(), request.seatNumber(), request.priceCents());
        Seat savedSeat = seatRepository.save(seat);
        return SeatResponse.from(savedSeat);
    }
}
