package io.github.themusicianone.ticketing_backend.order;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TicketRepository extends JpaRepository<Ticket, Long> {

    @Query("""
            SELECT t FROM Ticket t
            JOIN FETCH t.seat
            WHERE t.order.id = :orderId
            ORDER BY t.seat.id
            """)
    List<Ticket> findWithSeatsByOrderId(@Param("orderId") Long orderId);
}