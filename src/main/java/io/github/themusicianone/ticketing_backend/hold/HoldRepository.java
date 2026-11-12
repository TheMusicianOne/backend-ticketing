package io.github.themusicianone.ticketing_backend.hold;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
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


    @Query("""
        SELECT h.id FROM Hold h
        WHERE h.status = :active
          AND h.expiresAt <= :now
        ORDER BY h.expiresAt
        """)
    List<Long> findDueHoldIds(@Param("active") HoldStatus active,
                              @Param("now") Instant now,
                              Pageable pageable);


    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
        UPDATE Hold h
        SET h.status = :expired
        WHERE h.id IN :holdIds
          AND h.status = :active
        """)
    int expireHolds(@Param("holdIds") Collection<Long> holdIds,
                    @Param("expired") HoldStatus expired,
                    @Param("active") HoldStatus active);


    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
        UPDATE Hold h
        SET h.status = :to
        WHERE h.id = :holdId AND h.status = :from
        """)
    int changeStatus(@Param("holdId") Long holdId,
                     @Param("from") HoldStatus from,
                     @Param("to") HoldStatus to);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
        UPDATE Hold h
        SET h.status = :paymentPending
        WHERE h.id = :holdId
          AND h.user.id = :userId
          AND h.status = :active
          AND h.expiresAt > :now
        """)
    int startPayment(@Param("holdId") Long holdId,
                     @Param("userId") Long userId,
                     @Param("active") HoldStatus active,
                     @Param("paymentPending") HoldStatus paymentPending,
                     @Param("now") Instant now);
}
