package io.github.themusicianone.ticketing_backend.venue;

import jakarta.persistence.*;


// we wanted id, venue_id, name, starts_at, created_at
@Entity
@Table(name= "venues")
public class Venue {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;


    private String city;

    private String name;

    protected Venue() {

    }

    public Venue(String name, String city) {
        this.city = city;
        this.name = name;
    }
    public Long getId() {
        return id;
    }
    public String getCity() {
        return city;
    }
    public String getName() {
        return name;
    }


}
