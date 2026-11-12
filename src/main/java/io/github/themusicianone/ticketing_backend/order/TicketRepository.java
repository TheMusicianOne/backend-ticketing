package io.github.themusicianone.ticketing_backend.order;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TicketRepository extends JpaRepository<Ticket, Long> {
    List<Ticket> findByOrder_IdOrderById(Long orderId);
}