package io.github.themusicianone.ticketing_backend.hold;

import io.github.themusicianone.ticketing_backend.seat.SeatRepository;
import io.github.themusicianone.ticketing_backend.seat.SeatStatus;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.util.Set;

@Component
@ConditionalOnProperty(name = "app.holds.strategy", havingValue = "conditional", matchIfMissing = true)
class ConditionalUpdateStrategy implements SeatHoldStrategy {

    private SeatRepository seatRepository;

    public ConditionalUpdateStrategy(SeatRepository seatRepository) {
        this.seatRepository = seatRepository;
    }

    @Override
    public void holdSeats(Hold hold, Long eventId, Set<Long> seatIds) {
        int updated = seatRepository.holdSeats(hold, eventId, seatIds, SeatStatus.HELD, SeatStatus.AVAILABLE);
        if (updated != seatIds.size()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "One or more seats are not available");
        }
    }
}