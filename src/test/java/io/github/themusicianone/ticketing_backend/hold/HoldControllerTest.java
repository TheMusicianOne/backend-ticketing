package io.github.themusicianone.ticketing_backend.hold;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.stream.Collectors;

import io.github.themusicianone.ticketing_backend.IntegrationTest;
import io.github.themusicianone.ticketing_backend.common.PageResponse;
import io.github.themusicianone.ticketing_backend.seat.SeatResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.client.EntityExchangeResult;
import org.springframework.test.web.servlet.client.RestTestClient;

class HoldControllerTest extends IntegrationTest {

    private String adminToken;

    @BeforeEach
    void setUp() {
        adminToken = adminToken();
    }

    // ---- Creating holds ----------------------------------------------------

    @Test
    void customerCanHoldSeats() {
        Long eventId = createEventWithSeats(10);
        List<Long> seatIds = firstSeatIds(eventId, 2);
        Instant before = Instant.now();

        EntityExchangeResult<HoldResponse> result = postHold(eventId, customerToken(), seatIds)
                .expectStatus().isCreated()
                .expectBody(HoldResponse.class)
                .returnResult();

        HoldResponse hold = result.getResponseBody();
        URI location = result.getResponseHeaders().getLocation();

        assertThat(hold.id()).isNotNull();
        assertThat(hold.eventId()).isEqualTo(eventId);
        assertThat(hold.status()).isEqualTo(HoldStatus.ACTIVE);
        assertThat(hold.seatIds()).containsExactlyInAnyOrderElementsOf(seatIds);
        assertThat(hold.expiresAt())
                .isAfter(before.plus(Duration.ofMinutes(9)))
                .isBefore(Instant.now().plus(Duration.ofMinutes(11)));
        assertThat(location.getPath()).isEqualTo("/holds/" + hold.id());
    }

    @Test
    void heldSeatsAreMarkedHeld() {
        Long eventId = createEventWithSeats(10);
        List<Long> seatIds = firstSeatIds(eventId, 3);

        postHold(eventId, customerToken(), seatIds)
                .expectStatus().isCreated();

        assertThat(seatIdsWithStatus(eventId, "HELD")).containsExactlyInAnyOrderElementsOf(seatIds);
    }

    @Test
    void seatHeldByAnotherCustomerCannotBeHeld() {
        Long eventId = createEventWithSeats(10);
        List<Long> seatIds = firstSeatIds(eventId, 1);

        postHold(eventId, customerToken(), seatIds)
                .expectStatus().isCreated();

        postHold(eventId, customerToken(), seatIds)
                .expectStatus().isEqualTo(409);
    }

    @Test
    void holdIsAllOrNothing() {
        Long eventId = createEventWithSeats(10);
        List<Long> seatIds = firstSeatIds(eventId, 2);
        Long alreadyHeld = seatIds.get(0);
        Long stillFree = seatIds.get(1);

        postHold(eventId, customerToken(), List.of(alreadyHeld))
                .expectStatus().isCreated();

        // Asks for one held and one free seat, so the whole request must fail
        postHold(eventId, customerToken(), List.of(alreadyHeld, stillFree))
                .expectStatus().isEqualTo(409);

        // The free seat must not have been left held by the failed request
        assertThat(seatIdsWithStatus(eventId, "HELD")).containsExactly(alreadyHeld);
    }

    @Test
    void seatFromAnotherEventCannotBeHeld() {
        Long eventId = createEventWithSeats(5);
        Long otherEventId = createEventWithSeats(5);
        List<Long> otherEventSeat = firstSeatIds(otherEventId, 1);

        postHold(eventId, customerToken(), otherEventSeat)
                .expectStatus().isEqualTo(409);

        assertThat(seatIdsWithStatus(otherEventId, "HELD")).isEmpty();
    }

    @Test
    void unknownSeatCannotBeHeld() {
        Long eventId = createEventWithSeats(5);

        postHold(eventId, customerToken(), List.of(999999L))
                .expectStatus().isEqualTo(409);
    }

    @Test
    void holdingSeatsForUnknownEventReturnsNotFound() {
        postHold(999999L, customerToken(), List.of(1L))
                .expectStatus().isNotFound();
    }

    // ---- Validation and limits ---------------------------------------------

    @Test
    void duplicateSeatIdsAreRejected() {
        Long eventId = createEventWithSeats(5);
        Long seatId = firstSeatIds(eventId, 1).get(0);

        postHold(eventId, customerToken(), List.of(seatId, seatId))
                .expectStatus().isBadRequest();
    }

    @Test
    void emptySeatListIsRejected() {
        Long eventId = createEventWithSeats(5);

        postHold(eventId, customerToken(), List.of())
                .expectStatus().isBadRequest();
    }

    @Test
    void moreThanSixSeatsInOneRequestIsRejected() {
        Long eventId = createEventWithSeats(10);

        postHold(eventId, customerToken(), firstSeatIds(eventId, 7))
                .expectStatus().isBadRequest();
    }

    @Test
    void customerCannotHoldMoreThanSixSeatsPerEvent() {
        Long eventId = createEventWithSeats(10);
        List<Long> seatIds = firstSeatIds(eventId, 7);
        String token = customerToken();

        postHold(eventId, token, seatIds.subList(0, 4))
                .expectStatus().isCreated();

        // 4 already held + 3 more = 7, over the limit of 6
        postHold(eventId, token, seatIds.subList(4, 7))
                .expectStatus().isEqualTo(409);
    }

    @Test
    void differentCustomersHaveSeparateLimits() {
        Long eventId = createEventWithSeats(12);
        List<Long> seatIds = firstSeatIds(eventId, 12);

        postHold(eventId, customerToken(), seatIds.subList(0, 6))
                .expectStatus().isCreated();

        postHold(eventId, customerToken(), seatIds.subList(6, 12))
                .expectStatus().isCreated();
    }

    // ---- Security ------------------------------------------------------------

    @Test
    void holdingSeatsRequiresLogin() {
        Long eventId = createEventWithSeats(5);

        postHold(eventId, null, firstSeatIds(eventId, 1))
                .expectStatus().isUnauthorized();
    }

    // ---- Helpers -------------------------------------------------------------

    private RestTestClient.ResponseSpec postHold(Long eventId, String token, List<Long> seatIds) {
        String ids = seatIds.stream().map(String::valueOf).collect(Collectors.joining(","));

        return client.post()
                .uri("/events/{eventId}/holds", eventId)
                .headers(h -> {
                    if (token != null) {
                        h.setBearerAuth(token);
                    }
                })
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"seatIds\":[" + ids + "]}")
                .exchange();
    }

    private Long createEventWithSeats(int seatCount) {
        Long venueId = client.post()
                .uri("/venues")
                .headers(h -> h.setBearerAuth(adminToken))
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"name\":\"Hold test venue\",\"city\":\"Amsterdam\"}")
                .exchange()
                .expectStatus().isCreated()
                .expectBody(IdOnly.class)
                .returnResult()
                .getResponseBody()
                .id();

        Long eventId = client.post()
                .uri("/events")
                .headers(h -> h.setBearerAuth(adminToken))
                .contentType(MediaType.APPLICATION_JSON)
                .body("""
                        {"name":"Hold test","startsAt":"2099-06-12T20:00:00Z","venueId":%d}
                        """.formatted(venueId))
                .exchange()
                .expectStatus().isCreated()
                .expectBody(IdOnly.class)
                .returnResult()
                .getResponseBody()
                .id();

        client.post()
                .uri("/events/{eventId}/seats/generate", eventId)
                .headers(h -> h.setBearerAuth(adminToken))
                .contentType(MediaType.APPLICATION_JSON)
                .body("""
                        {"sections":[{"name":"Floor","rows":1,"seatsPerRow":%d,"priceCents":5000}]}
                        """.formatted(seatCount))
                .exchange()
                .expectStatus().isCreated();

        return eventId;
    }

    private List<Long> firstSeatIds(Long eventId, int count) {
        return seats(eventId, "/events/{eventId}/seats?size=" + count).stream()
                .map(SeatResponse::id)
                .toList();
    }

    private List<Long> seatIdsWithStatus(Long eventId, String status) {
        return seats(eventId, "/events/{eventId}/seats?size=100&status=" + status).stream()
                .map(SeatResponse::id)
                .toList();
    }

    private List<SeatResponse> seats(Long eventId, String uri) {
        PageResponse<SeatResponse> page = client.get()
                .uri(uri, eventId)
                .exchange()
                .expectStatus().isOk()
                .expectBody(new ParameterizedTypeReference<PageResponse<SeatResponse>>() {})
                .returnResult()
                .getResponseBody();

        return page.content();
    }

    private record IdOnly(Long id) {
    }
}