package io.github.themusicianone.ticketing_backend.hold;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface HoldRepository extends JpaRepository<Hold, Long> {
    Optional<Hold> findByIdAndUser_Id(Long id, Long userId);
}
