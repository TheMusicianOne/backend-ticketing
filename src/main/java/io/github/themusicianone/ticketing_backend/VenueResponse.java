package io.github.themusicianone.ticketing_backend;

record VenueResponse(Long id, String name, String city) {
    static VenueResponse from(Venue venue) {
        return new VenueResponse(venue.getId(), venue.getName(), venue.getCity());
    }
}
