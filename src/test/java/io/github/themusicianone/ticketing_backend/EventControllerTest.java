package io.github.themusicianone.ticketing_backend;

import java.net.URI;
import java.time.Instant;

import io.github.themusicianone.ticketing_backend.event.CreateEventRequest;
import io.github.themusicianone.ticketing_backend.venue.CreateVenueRequest;
import io.github.themusicianone.ticketing_backend.venue.VenueResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.client.RestTestClient;

class EventControllerTest extends IntegrationTest {

    private String adminToken;

    @BeforeEach
    void authenticateAsAdmin() {
        adminToken = adminToken();
    }

    private static final Instant FUTURE_DATE = Instant.parse("2099-06-12T20:00:00Z");

    @Test
    void createdEventCanBeFetched() {
        Long venueId = createVenue();
        CreateEventRequest request = new CreateEventRequest("Test concert", FUTURE_DATE, venueId);

        URI location = client.post()
                .uri("/events")
                .headers(h -> h.setBearerAuth(adminToken))
                .contentType(MediaType.APPLICATION_JSON)
                .body(request)
                .exchange()
                .expectStatus().isCreated()
                .expectBody()
                .jsonPath("$.name").isEqualTo("Test concert")
                .jsonPath("$.venueId").isEqualTo(venueId)
                .returnResult()
                .getResponseHeaders()
                .getLocation();

        client.get()
                .uri(location)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.name").isEqualTo("Test concert")
                .jsonPath("$.venueId").isEqualTo(venueId);
    }

    @Test
    void blankNameIsRejected() {
        CreateEventRequest request = new CreateEventRequest("", FUTURE_DATE, createVenue());

        postEvent(request)
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.errors.name").exists();
    }

    @Test
    void pastDateIsRejected() {
        CreateEventRequest request = new CreateEventRequest(
                "Test concert",
                Instant.parse("2020-01-01T20:00:00Z"),
                createVenue());

        postEvent(request)
                .expectStatus().isBadRequest();
    }

    @Test
    void missingVenueIsRejected() {
        CreateEventRequest request = new CreateEventRequest("Test concert", FUTURE_DATE, null);

        postEvent(request)
                .expectStatus().isBadRequest();
    }

    @Test
    void unknownVenueIsRejected() {
        CreateEventRequest request = new CreateEventRequest("Test concert", FUTURE_DATE, 999999L);

        postEvent(request)
                .expectStatus().isBadRequest();
    }

    @Test
    void unknownEventReturnsNotFound() {
        client.get()
                .uri("/events/999999")
                .exchange()
                .expectStatus().isNotFound();
    }

    private RestTestClient.ResponseSpec postEvent(CreateEventRequest request) {
        return client.post()
                .uri("/events")
                .headers(h -> h.setBearerAuth(adminToken))
                .contentType(MediaType.APPLICATION_JSON)
                .body(request)
                .exchange();
    }

    private Long createVenue() {
        VenueResponse venue = client.post()
                .uri("/venues")
                .headers(h -> h.setBearerAuth(adminToken))
                .contentType(MediaType.APPLICATION_JSON)
                .body(new CreateVenueRequest("Test venue", "Amsterdam"))
                .exchange()
                .expectStatus().isCreated()
                .expectBody(VenueResponse.class)
                .returnResult()
                .getResponseBody();

        return venue.id();
    }

    @Test
    void eventsArePaginated() {
        Long venueId = createVenue();
        for (int i = 1; i <= 3; i++) {
            postEvent(new CreateEventRequest("Concert " + i, FUTURE_DATE, venueId))
                    .expectStatus().isCreated();
        }

        client.get()
                .uri("/events?page=0&size=2")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.content.length()").isEqualTo(2)
                .jsonPath("$.size").isEqualTo(2);
    }
}