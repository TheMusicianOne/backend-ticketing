package io.github.themusicianone.ticketing_backend.hold;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import io.github.themusicianone.ticketing_backend.IntegrationTest;
import io.github.themusicianone.ticketing_backend.user.User;
import io.github.themusicianone.ticketing_backend.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.test.web.servlet.client.EntityExchangeResult;

/**
 * Many customers try to hold the same seats at exactly the same moment.
 * The database, not luck, must decide who wins: never two holds on one seat,
 * never a half-finished hold, and never a server error.
 */
abstract class HoldRaceTest extends IntegrationTest {

    private static final int CUSTOMERS = 200;

    @Autowired
    private SeatHoldStrategy seatHoldStrategy;

    /** The strategy each subclass expects Spring to have injected. */
    protected abstract Class<? extends SeatHoldStrategy> expectedStrategy();

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JwtEncoder jwtEncoder;

    @Autowired
    private JdbcTemplate jdbc;

    private String adminToken;

    @BeforeEach
    void setUp() {
        adminToken = adminToken();
    }

    @Test
    void usesTheExpectedStrategy() {
        assertThat(seatHoldStrategy).isInstanceOf(expectedStrategy());
    }

    @RepeatedTest(5)
    void exactlyOneCustomerGetsTheSeat() throws Exception {
        Long eventId = createEventWithSeats(1);
        Long seatId = seatIds(eventId).get(0);
        List<String> tokens = createCustomers(CUSTOMERS);

        List<Callable<Attempt>> attempts = tokens.stream()
                .<Callable<Attempt>>map(token -> () -> tryHold(eventId, token, List.of(seatId)))
                .toList();

        List<Attempt> results = race(attempts);

        assertNoServerErrors(results);
        assertThat(countStatus(results, 201)).as("successful holds").isEqualTo(1);
        assertThat(countStatus(results, 409)).as("rejected holds").isEqualTo(CUSTOMERS - 1);

        // Check the database directly, not just the HTTP responses
        assertThat(heldSeats(eventId)).as("held seats").isEqualTo(1);
        assertThat(activeHolds(eventId)).as("active holds").isEqualTo(1);
    }

    @RepeatedTest(5)
    void overlappingHoldsNeverShareASeat() throws Exception {
        Long eventId = createEventWithSeats(4);
        List<Long> s = seatIds(eventId);

        // Every pair overlaps with others, so at most two of them can succeed
        List<List<Long>> pairs = List.of(
                List.of(s.get(0), s.get(1)),
                List.of(s.get(1), s.get(2)),
                List.of(s.get(2), s.get(3)),
                List.of(s.get(3), s.get(0)),
                List.of(s.get(0), s.get(2)),
                List.of(s.get(1), s.get(3)));

        List<String> tokens = createCustomers(CUSTOMERS);
        List<Callable<Attempt>> attempts = new ArrayList<>();
        for (int i = 0; i < tokens.size(); i++) {
            String token = tokens.get(i);
            List<Long> pair = pairs.get(i % pairs.size());
            attempts.add(() -> tryHold(eventId, token, pair));
        }

        List<Attempt> results = race(attempts);

        assertNoServerErrors(results);
        assertThat(results).allMatch(r -> r.status() == 201 || r.status() == 409);

        long successes = countStatus(results, 201);
        assertThat(successes).as("successful holds").isBetween(1L, 2L);

        // Every successful hold got both of its seats, and no seat was held twice
        assertThat(activeHolds(eventId)).as("active holds").isEqualTo(successes);
        assertThat(heldSeats(eventId)).as("held seats").isEqualTo(2 * successes);
        assertThat(activeHoldsWithoutExactlyTwoSeats(eventId)).as("half-finished holds").isZero();
    }

    // ---- Running requests at the same moment ---------------------------------

    /**
     * Starts every attempt on its own thread, makes them all wait at a gate,
     * then opens the gate so they hit the API together.
     */
    private <T> List<T> race(List<Callable<T>> attempts) throws Exception {
        CountDownLatch ready = new CountDownLatch(attempts.size());
        CountDownLatch start = new CountDownLatch(1);

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<T>> futures = new ArrayList<>();
            for (Callable<T> attempt : attempts) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    return attempt.call();
                }));
            }

            ready.await();
            start.countDown();

            List<T> results = new ArrayList<>();
            for (Future<T> future : futures) {
                results.add(future.get(60, TimeUnit.SECONDS));
            }
            return results;
        }
    }

    private Attempt tryHold(Long eventId, String token, List<Long> seatIds) {
        String ids = seatIds.stream().map(String::valueOf).collect(Collectors.joining(","));

        EntityExchangeResult<String> result = client.post()
                .uri("/events/{eventId}/holds", eventId)
                .headers(h -> h.setBearerAuth(token))
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"seatIds\":[" + ids + "]}")
                .exchange()
                .expectBody(String.class)
                .returnResult();

        return new Attempt(result.getStatus().value(), result.getResponseBody());
    }

    private record Attempt(int status, String body) {
    }

    // ---- Assertions ------------------------------------------------------------

    private void assertNoServerErrors(List<Attempt> results) {
        List<Attempt> errors = results.stream().filter(r -> r.status() >= 500).toList();
        assertThat(errors)
                .as("Server errors during the race. A deadlock or lock timeout is the usual cause: %s", errors)
                .isEmpty();
    }

    private long countStatus(List<Attempt> results, int status) {
        return results.stream().filter(r -> r.status() == status).count();
    }

    private long heldSeats(Long eventId) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM seats WHERE event_id = ? AND status = 'HELD'",
                Long.class, eventId);
    }

    private long activeHolds(Long eventId) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM holds WHERE event_id = ? AND status = 'ACTIVE'",
                Long.class, eventId);
    }

    private long activeHoldsWithoutExactlyTwoSeats(Long eventId) {
        return jdbc.queryForObject("""
                SELECT count(*) FROM holds h
                WHERE h.event_id = ?
                  AND h.status = 'ACTIVE'
                  AND (SELECT count(*) FROM seats s WHERE s.hold_id = h.id) <> 2
                """, Long.class, eventId);
    }

    // ---- Test data ---------------------------------------------------------------

    /**
     * Creates customers directly in the database and signs their tokens here.
     * Registering 200 users through the API would run bcrypt 400 times and take
     * far longer than the race itself, which isn't what this test is about.
     */
    private List<String> createCustomers(int count) {
        String passwordHash = passwordEncoder.encode("race-test-password");
        List<String> tokens = new ArrayList<>();

        for (int i = 0; i < count; i++) {
            User user = userRepository.save(
                    new User("race-" + UUID.randomUUID() + "@example.com", passwordHash));
            tokens.add(customerTokenFor(user));
        }
        return tokens;
    }

    private String customerTokenFor(User user) {
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer("ticketing-backend")
                .issuedAt(now)
                .expiresAt(now.plus(1, ChronoUnit.HOURS))
                .subject(user.getId().toString())
                .claim("roles", List.of("CUSTOMER"))
                .build();

        return jwtEncoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
                .getTokenValue();
    }

    private Long createEventWithSeats(int seatCount) {
        Long venueId = client.post()
                .uri("/venues")
                .headers(h -> h.setBearerAuth(adminToken))
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"name\":\"Race venue\",\"city\":\"Amsterdam\"}")
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
                        {"name":"Race test","startsAt":"2099-06-12T20:00:00Z","venueId":%d}
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

    private List<Long> seatIds(Long eventId) {
        return jdbc.queryForList(
                "SELECT id FROM seats WHERE event_id = ? ORDER BY id", Long.class, eventId);
    }

    private record IdOnly(Long id) {
    }
}