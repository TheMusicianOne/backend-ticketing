package io.github.themusicianone.ticketing_backend.hold;


import io.github.themusicianone.ticketing_backend.seat.Seat;
import io.github.themusicianone.ticketing_backend.seat.SeatNotAvailableException;
import io.github.themusicianone.ticketing_backend.seat.SeatRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

@Component
@ConditionalOnProperty(name = "app.holds.strategy", havingValue = "optimistic")
public class OptimisticLockingStrategy implements SeatHoldStrategy{

    private final SeatRepository seatRepository;

    public OptimisticLockingStrategy(SeatRepository seatRepository) {
        this.seatRepository = seatRepository;
    }
    @Override
    public void holdSeats(Hold hold, Long eventId, Set<Long> seatIds) {
        List<Seat> seats = seatRepository.findAllByIdInAndEvent_IdOrderById(seatIds, eventId);
        if (seats.size() != seatIds.size()) {
            throw new SeatNotAvailableException();
        }

        seats.forEach(seat -> seat.holdFor(hold));
        seatRepository.flush();
    }
}
