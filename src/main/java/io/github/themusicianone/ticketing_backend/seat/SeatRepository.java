package io.github.themusicianone.ticketing_backend.seat;

import io.github.themusicianone.ticketing_backend.hold.Hold;
import io.github.themusicianone.ticketing_backend.hold.HoldStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface SeatRepository extends JpaRepository<Seat, Long> {
    Optional<Seat> findByIdAndEvent_Id(Long id, Long eventId);

    boolean existsByEvent_Id(Long eventId);

    Page<Seat> findByEvent_Id(Long eventId, Pageable pageable);

    Page<Seat> findByEvent_IdAndStatus(Long eventId, SeatStatus status, Pageable pageable);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""

            Update Seat s
                SET s.status = :held,s.hold=:hold, s.version=s.version+1
                WHERE s.id IN :seatIds
                     AND s.event.id = :eventId
                     AND s.status = :available
        """)
    int holdSeats(@Param("hold") Hold hold,
                  @Param("eventId") Long eventId,
                  @Param("seatIds") Collection<Long> seatIds,
                  @Param("held") SeatStatus held,
                  @Param("available") SeatStatus available);



    long countByEvent_IdAndHold_User_IdAndHold_StatusIn(Long eventId, Long userId, Collection<HoldStatus> statuses);

    @Query("SELECT s.id FROM Seat s WHERE s.hold.id = :holdId ORDER BY s.id")
    List<Long> findIdsByHoldId(@Param("holdId") Long holdId);



    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
        UPDATE Seat s
        SET s.status = :available, s.hold = null, s.version = s.version + 1
        WHERE s.hold.id = :holdId
          AND s.status = :held
        """)
    int releaseSeats(@Param("holdId") Long holdId,
                     @Param("available") SeatStatus available,
                     @Param("held") SeatStatus held);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            UPDATE Seat s
            SET s.status = :available, s.hold = null, s.version = s.version + 1
            WHERE s.hold.id IN :holdIds
              AND s.status = :held
            """)
    int releaseSeatsForHolds(@Param("holdIds") Collection<Long> holdIds,
                             @Param("available") SeatStatus available,
                             @Param("held") SeatStatus held);

    List<Seat> findAllByIdInAndEvent_IdOrderById(Collection<Long> ids, Long eventId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
        SELECT s FROM Seat s
        WHERE s.id IN :seatIds AND s.event.id = :eventId
        ORDER BY s.id
        """)
    List<Seat> findForUpdate(@Param("seatIds") Collection<Long> seatIds,
                             @Param("eventId") Long eventId);

}

