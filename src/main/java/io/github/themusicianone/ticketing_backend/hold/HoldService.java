package io.github.themusicianone.ticketing_backend.hold;


import io.github.themusicianone.ticketing_backend.event.Event;
import io.github.themusicianone.ticketing_backend.event.EventRepository;
import io.github.themusicianone.ticketing_backend.seat.Seat;
import io.github.themusicianone.ticketing_backend.seat.SeatRepository;
import io.github.themusicianone.ticketing_backend.seat.SeatStatus;
import io.github.themusicianone.ticketing_backend.user.User;
import io.github.themusicianone.ticketing_backend.user.UserRepository;
import org.springframework.data.domain.PageRequest;
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
    private static final int EXPIRY_BATCH_SIZE = 500;
    private static final Duration HOLD_DURATION = Duration.ofMinutes(10);
    private final HoldRepository holdRepository;
    private final EventRepository eventRepository;
    private final UserRepository userRepository;
    private final SeatRepository seatRepository;
    private final SeatHoldStrategy seatHoldStrategy;
    public HoldService(HoldRepository holdRepository, EventRepository eventRepository, UserRepository userRepository, SeatRepository seatRepository
    , SeatHoldStrategy seatHoldStrategy) {
        this.holdRepository = holdRepository;
        this.eventRepository = eventRepository;
        this.userRepository = userRepository;
        this.seatRepository = seatRepository;
        this.seatHoldStrategy = seatHoldStrategy;
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
        long alreadyHeld = seatRepository.countByEvent_IdAndHold_User_IdAndHold_StatusIn(
                eventId, userId, List.of(HoldStatus.ACTIVE,HoldStatus.PAYMENT_PENDING));

        if (alreadyHeld + seatIds.size() > 6) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "You can hold at most 6 seats per event");
        }

        User user = userRepository.getReferenceById(userId);

        Hold hold = new Hold(user,event, Instant.now().plus(HOLD_DURATION));
        Hold holdSaved = holdRepository.save(hold);

        seatHoldStrategy.holdSeats(holdSaved,eventId,seatIds);

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

    @Transactional
    public int expireDueHolds(Instant now) {
        List<Long> dueHoldIds = holdRepository.findDueHoldIds(
                HoldStatus.ACTIVE, now, PageRequest.of(0, EXPIRY_BATCH_SIZE));

        if (dueHoldIds.isEmpty()) {
            return 0;
        }

        int expiredHolds = holdRepository.expireHolds(
                dueHoldIds, HoldStatus.EXPIRED, HoldStatus.ACTIVE);

        seatRepository.releaseSeatsForHolds(
                dueHoldIds, SeatStatus.AVAILABLE, SeatStatus.HELD);

        return expiredHolds;
    }
}
