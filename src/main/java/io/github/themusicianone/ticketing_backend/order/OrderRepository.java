package io.github.themusicianone.ticketing_backend.order;

import jakarta.transaction.Transactional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface OrderRepository extends JpaRepository<Order, Long> {
    Optional<Order> findByIdAndUser_Id(Long id, Long userId);
    Optional<Order> findByUser_IdAndIdempotencyKey(Long userId, String idempotencyKey);
    Page<Order> findByUser_Id(Long userId, Pageable pageable);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
        UPDATE Order o
        SET o.status = :newStatus, o.updatedAt = :now
        WHERE o.id = :orderId AND o.status = :pending
        """)
    int finishPending(@Param("orderId") Long orderId,
                      @Param("newStatus") OrderStatus newStatus,
                      @Param("pending") OrderStatus pending,
                      @Param("now") Instant now);

    Optional<Order> findByPaymentId(String paymentId);

    @Query("""
        SELECT o FROM Order o
        WHERE o.status = :pending
          AND o.paymentId IS NOT NULL
          AND o.createdAt <= :checkBefore
        ORDER BY o.createdAt
        """)
    List<Order> findPendingToReconcile(@Param("pending") OrderStatus pending,
                                       @Param("checkBefore") Instant checkBefore,
                                       Pageable pageable);
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            UPDATE Order o
            SET o.refundRequiredAt = :now, o.updatedAt = :now
            WHERE o.status IN :statuses AND o.refundRequiredAt IS NULL AND o.id= :orderId           
                        
            """)
    int markRefundRequired(@Param("orderId") Long orderId,
                           @Param("now") Instant now,
                           @Param("statuses") Collection<OrderStatus> statuses);


    @Query("""
           SELECT o FROM Order o
                      WHERE o.refundRequiredAt IS NOT NULL AND 
                            o.refundedAt IS NULL 
                      ORDER BY o.refundRequiredAt ASC
           """)
    List<Order> findRefundsToProcess( Pageable pageable);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Transactional
    @Query("""
            UPDATE Order o
            SET o.refundedAt = :now, o.updatedAt = :now, o.status = :refunded
            WHERE o.id = :orderId AND o.refundRequiredAt IS NOT NULL AND o.refundedAt IS NULL
            """)
    int markRefunded(@Param("orderId") Long orderId, @Param("now") Instant now, @Param("refunded") OrderStatus refunded);






}
