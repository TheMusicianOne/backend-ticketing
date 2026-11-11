package io.github.themusicianone.ticketing_backend.hold;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface HoldRepository extends JpaRepository<Hold, Long> {
    Optional<Hold> findByIdAndUser_Id(Long id, Long userId);


    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
        UPDATE Hold h
        SET h.status = :released
        WHERE h.id = :holdId
          AND h.user.id = :userId
          AND h.status = :active
        """)
    int releaseHold(@Param("holdId") Long holdId,
                    @Param("userId") Long userId,
                    @Param("released") HoldStatus released,
                    @Param("active") HoldStatus active);
}
