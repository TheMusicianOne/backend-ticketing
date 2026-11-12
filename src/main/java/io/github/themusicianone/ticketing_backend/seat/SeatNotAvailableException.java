package io.github.themusicianone.ticketing_backend.seat;

public class SeatNotAvailableException extends RuntimeException {

    public SeatNotAvailableException() {
        super("One or more seats are not available");
    }
}