package io.github.themusicianone.ticketing_backend;
import jakarta.persistence.*;


//id, event_id, section, row_label, seat_number, price_cents, status
@Entity
@Table(name = "seats")
public class Seat {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "event_id")
    private Event event;

    private String section;
    private String rowLabel;

    private Integer seatNumber;
    private Integer priceCents;

    @Enumerated(EnumType.STRING)
    private SeatStatus status;

    protected Seat() {

    }

    public Seat(Event event, String section,String rowLabel, Integer seatNumber, Integer priceCents) {

        this.section = section;
        this.event = event;
        this.rowLabel = rowLabel;
        this.seatNumber = seatNumber;
        this.priceCents = priceCents;
        this.status = SeatStatus.AVAILABLE;

    }

    public Long getId() {
        return id;
    }
    public String getSection() {
        return section;

    }
    public Long getEventId() {
        return event.getId();
    }
    public String getRowLabel() {
        return rowLabel;
    }
    public Integer getSeatNumber() {
        return seatNumber;
    }
    public Integer getPriceCents() {
        return priceCents;
    }

    public SeatStatus getStatus() {
        return status;
    }





}
