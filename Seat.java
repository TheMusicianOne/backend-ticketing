package io.github.themusicianone.ticketing_backend;
import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;
import java.time.Instant;


//id, event_id, section, row_label, seat_number, price_cents, status
@Entity
@Table(name = "seats")
public class Seat {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String section;

    private Long event_id;

    private String event_name;

    private Long row_label;
    private Long seat_number;
    private Long price_cents;
    private String status;

    protected Seat() {

    }

    public Seat(Long id, String section, Long event_id, Long row_label, Long seat_number, Long price_cents, String status) {
        this.id = id;
        this.section = section;
        this.event_id = event_id;
        this.row_label = row_label;
        this.seat_number = seat_number;
        this.price_cents = price_cents;
        this.status = status;

    }

    public Long getId() {
        return id;
    }
    public String getSection() {
        return section;

    }
    public Long getEvent_id() {
        return event_id;
    }
    public String getEvent_name() {
        return event_name;
    }
    public Long getRow_label() {
        return row_label;
    }
    public Long getSeat_number() {
        return seat_number;
    }
    public Long getPrice_cents() {
        return price_cents;
    }

    public String getStatus() {
        return status;
    }





}
