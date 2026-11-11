package io.github.themusicianone.ticketing_backend.hold;


import io.github.themusicianone.ticketing_backend.event.Event;
import io.github.themusicianone.ticketing_backend.user.User;
import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;

import static io.github.themusicianone.ticketing_backend.hold.HoldStatus.*;

@Entity
@Table(name="holds")
public class Hold {

    @Id
    @GeneratedValue(strategy=GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch=FetchType.LAZY, optional=false)
    private User user;

    @ManyToOne(fetch=FetchType.LAZY, optional=false)
    private Event event;

    @Enumerated(EnumType.STRING)
    private HoldStatus status;

    private Instant expiresAt;

    @CreationTimestamp
    @Column(updatable = false)
    private Instant createdAt;

    protected Hold() {}

    public Hold(User user, Event event, Instant expiresAt) {
        this.user = user;
        this.event = event;
        this.expiresAt = expiresAt;
        this.status = ACTIVE;

    }

    public Long getId() {
        return id;
    }

    public User getUser() {
        return user;
    }

    public Event getEvent() {
        return event;
    }

    public HoldStatus getStatus() {
        return status;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void release() {
        this.status = HoldStatus.RELEASED;
    }

    public void expire() {
        this.status = HoldStatus.EXPIRED;
    }

    public boolean isOwnedBy(Long userId) {
        return user.getId().equals(userId);
    }


}
