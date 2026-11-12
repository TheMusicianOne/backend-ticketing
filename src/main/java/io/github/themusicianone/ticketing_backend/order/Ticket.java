package io.github.themusicianone.ticketing_backend.order;


import io.github.themusicianone.ticketing_backend.seat.Seat;
import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;

@Entity
@Table(name= "tickets")
public class Ticket {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_id")
    private Order order;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "seat_id")
    private Seat seat;

    private String code;

    @CreationTimestamp
    @Column(updatable = false)
    private Instant issuedAt;

    protected Ticket() {}

    public Ticket(Order order, Seat seat, String code) {
        this.order = order;
        this.seat = seat;
        this.code = code;
    }
    public long getId() {
        return id;
    }
    public Order getOrder() {
        return order;
    }
    public Seat getSeat() {
        return seat;
    }
    public String getCode() {
        return code;
    }
    public Instant getIssuedAt() {
        return issuedAt;
    }
    

}
