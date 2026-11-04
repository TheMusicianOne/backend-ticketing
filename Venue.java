package io.github.themusicianone.ticketing_backend;

import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;
import java.time.Instant;


// we wanted id, venue_id, name, starts_at, created_at
@Entity
@Table(name= "venues")
public class Venue {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;


    private String City;

    private String name;

    private String description;

    protected Venue() {

    }

    public Venue(String city, String name, String description) {
        this.City = city;
        this.name = name;
        this.description = description;
    }
    public Long getId() {
        return id;
    }
    public String getCity() {
        return City;
    }
    public String getName() {
        return name;
    }
    public String getDescription() {
        return description;
    }


}
