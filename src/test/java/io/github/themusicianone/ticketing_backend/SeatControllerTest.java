package io.github.themusicianone.ticketing_backend;

import java.net.URI;
import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.client.RestTestClient;

@Import(TestcontainersConfiguration.class)
@AutoConfigureRestTestClient
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SeatControllerTest {

    @Autowired
    private RestTestClient client;

    @Test
    void createdSeatCanBeFetched() {
        Long eventId = createEvent();

        URI location = client.post()
                .uri("/events/{eventId}/seats", eventId)
                .contentType(MediaType.APPLICATION_JSON)
                .body(new CreateSeatRequest("A", "1", 5, 2500))
                .exchange()
                .expectStatus().isCreated()
                .expectBody()
                .jsonPath("$.eventId").isEqualTo(eventId)
                .jsonPath("$.status").isEqualTo("AVAILABLE")
                .returnResult()
                .getResponseHeaders()
                .getLocation();

        client.get()
                .uri(location)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.eventId").isEqualTo(eventId)
                .jsonPath("$.seatNumber").isEqualTo(5);
    }

    private Long createEvent() {
        VenueResponse venue = client.post()
                .uri("/venues")
                .contentType(MediaType.APPLICATION_JSON)
                .body(new CreateVenueRequest("Test venue", "Amsterdam"))
                .exchange()
                .expectStatus().isCreated()
                .expectBody(VenueResponse.class)
                .returnResult()
                .getResponseBody();

        return client.post()
                .uri("/events")
                .contentType(MediaType.APPLICATION_JSON)
                .body(new CreateEventRequest("Test concert", Instant.parse("2099-06-12T20:00:00Z"), venue.id()))
                .exchange()
                .expectStatus().isCreated()
                .expectBody(EventResponse.class)
                .returnResult()
                .getResponseBody()
                .id();
    }


    @Test
    void seatCannotBeFetchedThroughAnotherEvent() {
        Long eventId = createEvent();
        Long otherEventId = createEvent();

        URI location = postSeat(eventId, new CreateSeatRequest("A", "1", 5, 2500))
                .expectStatus().isCreated()
                .expectBody()
                .returnResult()
                .getResponseHeaders()
                .getLocation();

        String seatId = location.getPath().substring(location.getPath().lastIndexOf('/') + 1);

        client.get()
                .uri("/events/{eventId}/seats/{seatId}", otherEventId, seatId)
                .exchange()
                .expectStatus().isNotFound();
    }

    @Test
    void creatingSeatForUnknownEventReturnsNotFound() {
        postSeat(999999L, new CreateSeatRequest("A", "1", 5, 2500))
                .expectStatus().isNotFound();
    }

    @Test
    void unknownSeatReturnsNotFound() {
        client.get()
                .uri("/events/{eventId}/seats/999999", createEvent())
                .exchange()
                .expectStatus().isNotFound();
    }

    @Test
    void invalidSeatsAreRejected() {
        Long eventId = createEvent();

        postSeat(eventId, new CreateSeatRequest("", "1", 5, 2500))
                .expectStatus().isBadRequest();
        postSeat(eventId, new CreateSeatRequest("A", "1", 0, 2500))
                .expectStatus().isBadRequest();
        postSeat(eventId, new CreateSeatRequest("A", "1", 5, -1))
                .expectStatus().isBadRequest();
    }

    private RestTestClient.ResponseSpec postSeat(Long eventId, CreateSeatRequest request) {
        return client.post()
                .uri("/events/{eventId}/seats", eventId)
                .contentType(MediaType.APPLICATION_JSON)
                .body(request)
                .exchange();
    }
}
