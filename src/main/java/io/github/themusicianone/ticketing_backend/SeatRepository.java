package io.github.themusicianone.ticketing_backend;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface SeatRepository extends JpaRepository<Seat, Long> {
    Optional<Seat> findByIdAndEvent_Id(Long id, Long eventId);
    boolean existsByEvent_Id(Long eventId);
    Page<Seat> findByEvent_Id(Long eventId, Pageable pageable);
    Page<Seat> findByEvent_IdAndStatus(Long eventId, SeatStatus status, Pageable pageable);
}
