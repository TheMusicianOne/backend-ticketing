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
class EventControllerTest {

    @Autowired
    private RestTestClient client;

    @Test
    void createdEventCanBeFetched() {
        CreateEventRequest request = new CreateEventRequest(
                "Test concert",
                Instant.parse("2099-06-12T20:00:00Z"));

        URI location = client.post()
                .uri("/events")
                .contentType(MediaType.APPLICATION_JSON)
                .body(request)
                .exchange()
                .expectStatus().isCreated()
                .expectBody()
                .returnResult()
                .getResponseHeaders()
                .getLocation();

        client.get()
                .uri(location)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.name").isEqualTo("Test concert");
    }

    @Test
    void blankNameIsRejected() {
        CreateEventRequest request = new CreateEventRequest(
                "",
                Instant.parse("2099-06-12T20:00:00Z"));

        client.post()
                .uri("/events")
                .contentType(MediaType.APPLICATION_JSON)
                .body(request)
                .exchange()
                .expectStatus().isBadRequest();
    }

    @Test
    void unknownEventReturnsNotFound() {
        client.get()
                .uri("/events/999999")
                .exchange()
                .expectStatus().isNotFound();
    }

}
