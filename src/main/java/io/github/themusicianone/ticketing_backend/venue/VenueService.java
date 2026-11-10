package io.github.themusicianone.ticketing_backend.venue;


import jakarta.transaction.Transactional;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Service
public class VenueService {

    private final VenueRepository venueRepository;
    public VenueService(VenueRepository venueRepository) {
        this.venueRepository = venueRepository;
    }

    Optional<VenueResponse> findById(long id) {
        Optional<Venue> venue = venueRepository.findById(id);

        if (venue.isPresent()){
            return Optional.of(VenueResponse.from(venue.get()));
        }
        return Optional.empty();
    }

    @Transactional
    VenueResponse create(CreateVenueRequest request) {


        Venue venue  = new Venue(request.name(), request.city());
        Venue savedVenue = venueRepository.save(venue);
        return VenueResponse.from(savedVenue);
    }
}
