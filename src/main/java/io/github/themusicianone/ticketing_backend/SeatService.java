package io.github.themusicianone.ticketing_backend;



import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.*;

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


    @Transactional
    GenerateSeatResponse generate(Long eventId, GenerateSeatRequest request) {
        Event event = eventRepository.findById(eventId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Event not found"));

        if (seatRepository.existsByEvent_Id(eventId)) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "Seats already generated for this event");
        }

        Set<String> sectionNames = new HashSet<>();
        for (SectionLayout section : request.sections()) {
            sectionNames.add(section.name());
        }

        if (sectionNames.size() != request.sections().size()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "Duplicate section names cannot exist");
        }
        List<Seat> newSeats = new ArrayList<>();
        for (SectionLayout section : request.sections()) {
            for (int row = 1; row <= section.rows(); row++) {
                for (int seatNo = 1; seatNo <= section.seatsPerRow(); seatNo++) {
                    newSeats.add(new Seat(event, section.name(),
                            String.valueOf(row), seatNo, section.priceCents()));
                }
            }
        }

        seatRepository.saveAll(newSeats);
        return new GenerateSeatResponse(eventId, newSeats.size());
    }

    @Transactional(readOnly = true)
    PageResponse<SeatResponse> findAll(Long eventId, SeatStatus status, Pageable pageable) {
        if (!eventRepository.existsById(eventId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Event not found");
        }

        Page<Seat> seats;
        if (status == null) {
            seats = seatRepository.findByEvent_Id(eventId, pageable);
        } else {
            seats = seatRepository.findByEvent_IdAndStatus(eventId, status, pageable);
        }

        return PageResponse.from(seats.map(SeatResponse::from));
    }
}
