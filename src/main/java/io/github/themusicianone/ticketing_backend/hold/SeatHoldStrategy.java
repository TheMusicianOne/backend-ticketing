package io.github.themusicianone.ticketing_backend.hold;

import java.util.Set;

public interface SeatHoldStrategy {

    void holdSeats(Hold hold, Long eventId, Set<Long> seatIds);
}
