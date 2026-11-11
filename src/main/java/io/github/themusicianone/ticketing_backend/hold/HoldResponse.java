package io.github.themusicianone.ticketing_backend.hold;

import java.time.Instant;
import java.util.List;

public record HoldResponse(Long id, Long eventId, HoldStatus status, Instant expiresAt, List<Long> seatIds) {
    public static HoldResponse from(Hold hold, List<Long> seatIds){
        return new HoldResponse(hold.getId(), hold.getEvent().getId(), hold.getStatus(),hold.getExpiresAt(), seatIds);
    }
}
