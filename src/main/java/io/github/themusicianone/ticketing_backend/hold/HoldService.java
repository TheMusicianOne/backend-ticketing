package io.github.themusicianone.ticketing_backend.hold;


import io.github.themusicianone.ticketing_backend.event.Event;
import io.github.themusicianone.ticketing_backend.event.EventRepository;
import io.github.themusicianone.ticketing_backend.seat.Seat;
import io.github.themusicianone.ticketing_backend.seat.SeatRepository;
import io.github.themusicianone.ticketing_backend.seat.SeatStatus;
import io.github.themusicianone.ticketing_backend.user.User;
import io.github.themusicianone.ticketing_backend.user.UserRepository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

@Service
public class HoldService {

    private final HoldRepository holdRepository;
    private final EventRepository eventRepository;
    private final UserRepository userRepository;
    private final SeatRepository seatRepository;
    public HoldService(HoldRepository holdRepository, EventRepository eventRepository, UserRepository userRepository, SeatRepository seatRepository
    ) {
        this.holdRepository = holdRepository;
        this.eventRepository = eventRepository;
        this.userRepository = userRepository;
        this.seatRepository = seatRepository;
    }


    @Transactional
    public HoldResponse create(Long eventId, Long userId, CreateHoldRequest request) {
        Optional<Event> opevent = eventRepository.findById(eventId);
        if (!opevent.isPresent()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Event does not exist");

        }
        Event event = opevent.get();
        Set<Long> seatIds = new HashSet<>(request.seatIds());

        if(seatIds.size() != request.seatIds().size()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Duplicate seat IDs");
        }
        long alreadyHeld = seatRepository.countByEvent_IdAndHold_User_IdAndHold_Status(
                eventId, userId, HoldStatus.ACTIVE);

        if (alreadyHeld + seatIds.size() > 6) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "You can hold at most 6 seats per event");
        }

        User user = userRepository.getReferenceById(userId);

        Hold hold = new Hold(user,event, Instant.now().plus(Duration.ofMinutes(10)));
        Hold holdSaved = holdRepository.save(hold);

        int available_holds = seatRepository.holdSeats(holdSaved,eventId,seatIds,SeatStatus.HELD, SeatStatus.AVAILABLE);

        if(available_holds != seatIds.size()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "One or more seats are not available");
        }

        return HoldResponse.from(holdSaved, List.copyOf(seatIds));

    }

    @Transactional(readOnly=true)
    public HoldResponse findForUser(Long holdId, Long userId) {
        Optional<Hold> ophold = holdRepository.findByIdAndUser_Id(holdId, userId);
        if (!ophold.isPresent()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Hold does not exist");
        }
        Hold hold = ophold.get();

        return HoldResponse.from(hold,seatRepository.findIdsByHoldId(holdId));
    }

    @Transactional
    public void release (Long holdId, Long userId) {
        int releaseResponse = holdRepository.releaseHold(holdId, userId, HoldStatus.RELEASED,HoldStatus.ACTIVE);
        if (releaseResponse == 0) {
            Optional<Hold> ophold = holdRepository.findByIdAndUser_Id(holdId, userId);
            if (!ophold.isPresent()) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Hold not found");

            }
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Hold is no longer active");
        }
        seatRepository.releaseSeats(holdId,SeatStatus.AVAILABLE, SeatStatus.HELD);
    }
}
