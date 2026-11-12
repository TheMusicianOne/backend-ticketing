package io.github.themusicianone.ticketing_backend.order;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface OrderRepository extends JpaRepository<Order, Long> {
    Optional<Order> findByIdAndUser_Id(Long id, Long userId);
    Optional<Order> findByUser_IdAndIdempotencyKey(Long userId, String idempotencyKey);
    Page<Order> findByUser_Id(Long userId, Pageable pageable);
}
