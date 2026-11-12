package io.github.themusicianone.ticketing_backend.order;

import io.github.themusicianone.ticketing_backend.event.Event;
import io.github.themusicianone.ticketing_backend.hold.Hold;
import io.github.themusicianone.ticketing_backend.user.User;
import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;

@Entity
@Table(name="orders")
public class Order {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private long id;


    @ManyToOne(fetch=FetchType.LAZY, optional = false)
    @JoinColumn(name = "event_id")
    private Event event;

    @ManyToOne(fetch=FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id")
    private User user;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "hold_id")
    private Hold hold;

    @Enumerated(EnumType.STRING)
    private OrderStatus status;

    private int totalCents;

    private String idempotencyKey;

    private String paymentId;
    private String checkoutUrl;

    private Instant paymentDeadline;

    @CreationTimestamp
    private Instant createdAt;

    @UpdateTimestamp
    private Instant updatedAt;

    protected Order() {

    }

    public Order(User user, Event event, Hold hold, int totalCents, String idempotencyKey, Instant paymentDeadline) {
        this.user = user;
        this.hold = hold;
        this.event = event;
        this.totalCents = totalCents;
        this.idempotencyKey = idempotencyKey;
        this.paymentDeadline = paymentDeadline;
        this.status = OrderStatus.PENDING;
    }

    public void attachPayment(String paymentId, String checkoutUrl) {
        this.paymentId = paymentId;
        this.checkoutUrl = checkoutUrl;
    }

    public long getId() {
        return id;

    }

    public Event getEvent() {
        return event;
    }
    public User getUser() {
        return user;
    }
    public Hold getHold() {
        return hold;
    }
    public OrderStatus getStatus() {
        return status;
    }
    public int getTotalCents() {
        return totalCents;
    }
    public String getIdempotencyKey() {
        return idempotencyKey;
    }
    public String getPaymentId() {
        return paymentId;
    }
    public String getCheckoutUrl() {
        return checkoutUrl;
    }
    public Instant getPaymentDeadline() {
        return paymentDeadline;
    }
    public Instant getCreatedAt() {
        return createdAt;
    }
    public Instant getUpdatedAt() {
        return updatedAt;
    }







}
