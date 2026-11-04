package io.github.themusicianone.ticketing_backend;
import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;
import java.time.Instant;

@Entity
@Table(name = "events")
public class Event {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    //private Long venueId;

    private String name;

    private Instant startsAt;

    @CreationTimestamp
    @Column(updatable = false)
    private Instant createdAt;

    protected Event() {
        // JPA needs an empty constructor to create objects from database rows
    }

    public Event(String name, Instant startsAt) {
        this.name = name;
        this.startsAt = startsAt;
    }

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;

    }
    public Instant getStartsAt() {
        return startsAt;
    }
    public Instant getCreatedAt() {
        return createdAt;
    }

    /*
    public Long getVenueId() {
        return venueId;
    }

     */
}
