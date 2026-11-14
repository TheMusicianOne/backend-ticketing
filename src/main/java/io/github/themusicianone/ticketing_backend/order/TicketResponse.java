package io.github.themusicianone.ticketing_backend.order;

import java.time.Instant;

import io.github.themusicianone.ticketing_backend.seat.Seat;

public record TicketResponse(
        Long id,
        String section,
        String rowLabel,
        Integer seatNumber,
        String code,
        Instant issuedAt
) {

    public static TicketResponse from(Ticket ticket) {
        Seat seat = ticket.getSeat();
        return new TicketResponse(
                ticket.getId(),
                seat.getSection(),
                seat.getRowLabel(),
                seat.getSeatNumber(),
                ticket.getCode(),
                ticket.getIssuedAt());
    }
}