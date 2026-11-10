package io.github.themusicianone.ticketing_backend.seat;

public record SeatResponse(Long id, String section, Long eventId, String rowLabel, Integer seatNumber, Integer priceCents, SeatStatus status) {
    static SeatResponse from(Seat seat) {
        return new SeatResponse(seat.getId(),seat.getSection(),seat.getEventId(), seat.getRowLabel(), seat.getSeatNumber(),seat.getPriceCents(), seat.getStatus());
    }
}
