package io.github.themusicianone.ticketing_backend;

import java.net.URI;

import io.github.themusicianone.ticketing_backend.venue.CreateVenueRequest;
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
class VenueControllerTest {

    @Autowired
    private RestTestClient client;

    @Test
    void createdVenueCanBeFetched() {
        URI location = postVenue(new CreateVenueRequest("Ziggo Dome", "Amsterdam"))
                .expectStatus().isCreated()
                .expectBody()
                .jsonPath("$.name").isEqualTo("Ziggo Dome")
                .jsonPath("$.city").isEqualTo("Amsterdam")
                .returnResult()
                .getResponseHeaders()
                .getLocation();

        client.get()
                .uri(location)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.name").isEqualTo("Ziggo Dome")
                .jsonPath("$.city").isEqualTo("Amsterdam");
    }

    @Test
    void blankNameIsRejected() {
        postVenue(new CreateVenueRequest("", "Amsterdam"))
                .expectStatus().isBadRequest();
    }

    @Test
    void blankCityIsRejected() {
        postVenue(new CreateVenueRequest("Ziggo Dome", ""))
                .expectStatus().isBadRequest();
    }

    @Test
    void unknownVenueReturnsNotFound() {
        client.get()
                .uri("/venues/999999")
                .exchange()
                .expectStatus().isNotFound();
    }

    private RestTestClient.ResponseSpec postVenue(CreateVenueRequest request) {
        return client.post()
                .uri("/venues")
                .contentType(MediaType.APPLICATION_JSON)
                .body(request)
                .exchange();
    }
}