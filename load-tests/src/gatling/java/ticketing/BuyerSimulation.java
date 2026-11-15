package ticketing;

import static io.gatling.javaapi.core.CoreDsl.*;
import static io.gatling.javaapi.http.HttpDsl.*;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

import io.gatling.javaapi.core.ChainBuilder;
import io.gatling.javaapi.core.FeederBuilder;
import io.gatling.javaapi.core.ScenarioBuilder;
import io.gatling.javaapi.core.Session;
import io.gatling.javaapi.core.Simulation;
import io.gatling.javaapi.http.HttpProtocolBuilder;

/**
 * A fan who holds seats, checks out, and then pays, declines, or walks away
 * at the payment provider, like real customers do.
 *
 * Run with: -DeventId=<event with many seats>
 * Optional: -DbaseUrl=... -DpspUrl=... -DusersPerSec=... -DdurationSeconds=...
 */
public class BuyerSimulation extends Simulation {

    // ---- Settings ----

    private static final String BASE_URL = System.getProperty("baseUrl", "http://localhost:8080");
    private static final String PSP_URL = System.getProperty("pspUrl", "http://localhost:8081");
    private static final String EVENT_ID = System.getProperty("eventId");
    private static final double USERS_PER_SEC = Double.parseDouble(System.getProperty("usersPerSec", "2"));
    private static final int DURATION_SECONDS = Integer.getInteger("durationSeconds", 30);
    // 0 = choose from every available seat. 10 = choose only from the first 10 (the "best" seats).
    private static final int PICK_FROM_FIRST = Integer.getInteger("pickFromFirst", 0);
    private static final int SEATS_PER_FAN = 2;
    private static final int MAX_ATTEMPTS = 3;
    private static final int MAX_STATUS_CHECKS = 10;

    // ---- Test data and protocol ----

    private final FeederBuilder<String> fans = csv("tokens.csv").circular();

    private final HttpProtocolBuilder httpProtocol = http
            .baseUrl(BASE_URL)
            .acceptHeader("application/json")
            .header("Authorization", "Bearer #{token}");

    // ---- Part 1: getting seats ----

    private static ChainBuilder lookAtSeats() {
        return exec(http("Available seats")
                .get("/events/" + EVENT_ID + "/seats?status=AVAILABLE&size=100")
                .check(status().is(200))
                .check(jsonPath("$.content[*].id").ofLong().findAll().optional().saveAs("seatIds")));
    }

    private static ChainBuilder pickSeats() {
        return exec(session -> {
            if (!session.contains("seatIds")) {
                return session.set("soldOut", true);
            }
            List<Long> available = new ArrayList<>(session.<Long>getList("seatIds"));
            if (PICK_FROM_FIRST > 0 && available.size() > PICK_FROM_FIRST) {
                available = new ArrayList<>(available.subList(0, PICK_FROM_FIRST));
            }
            Collections.shuffle(available, ThreadLocalRandom.current());
            List<Long> chosen = available.subList(0, Math.min(SEATS_PER_FAN, available.size()));
            String seatsJson = chosen.stream()
                    .map(String::valueOf)
                    .collect(Collectors.joining(",", "[", "]"));
            return session.set("seatsToHold", seatsJson);
        });
    }

    private static ChainBuilder holdSeats() {
        return exec(http("Hold seats")
                .post("/events/" + EVENT_ID + "/holds")
                .body(StringBody("{\"seatIds\":#{seatsToHold}}")).asJson()
                .check(status().in(201, 409).saveAs("holdStatus"))
                .check(jsonPath("$.id").optional().saveAs("holdId")));
    }

    private static ChainBuilder getSeats() {
        return exec(session -> session.set("held", false).set("soldOut", false))
                .asLongAs(session -> !session.getBoolean("held")
                        && !session.getBoolean("soldOut")
                        && counter(session, "attempt") < MAX_ATTEMPTS, "attempt")
                .on(
                        exec(session -> session.remove("seatIds"))
                                .exec(lookAtSeats())
                                .pause(Duration.ofMillis(500), Duration.ofSeconds(2))
                                .exec(pickSeats())
                                .doIf(session -> !session.getBoolean("soldOut")).then(
                                        exec(holdSeats())
                                                .exec(session -> session.set("held",
                                                        session.getInt("holdStatus") == 201))
                                )
                );
    }

    // ---- Part 2: checking out and paying ----

    /** Check out the hold. The Idempotency-Key is unique per fan and hold, like a real app would make it. */
    private static ChainBuilder checkOut() {
        return exec(http("Check out")
                .post("/holds/#{holdId}/checkout")
                .header("Idempotency-Key", "#{email}-#{holdId}")
                .check(status().is(201))
                .check(jsonPath("$.id").saveAs("orderId"))
                // The payment ID is the last part of the checkout URL: .../checkout/tr_abc123
                .check(jsonPath("$.checkoutUrl")
                        .transform(url -> url.substring(url.lastIndexOf('/') + 1))
                        .saveAs("paymentId")));
    }

    /** The fan "clicks a button" on the provider's checkout page. That's a different server, so a full URL. */
    private static ChainBuilder pressOnProviderPage(String button) {
        return exec(http("Provider: " + button)
                .post(PSP_URL + "/checkout/#{paymentId}/" + button)
                .check(status().in(200, 409)));
    }

    /**
     * After paying, the fan's app keeps refreshing the order until it's no longer PENDING.
     * The webhook usually confirms it within a second or two.
     */
    private static ChainBuilder waitForOrder() {
        return exec(session -> session.set("orderStatus", "PENDING"))
                .asLongAs(session -> "PENDING".equals(session.getString("orderStatus"))
                        && counter(session, "statusCheck") < MAX_STATUS_CHECKS, "statusCheck")
                .on(
                        pause(Duration.ofMillis(500))
                                .exec(http("Check order")
                                        .get("/orders/#{orderId}")
                                        .check(status().is(200))
                                        .check(jsonPath("$.status").saveAs("orderStatus")))
                );
    }

    /** What happens at the payment provider, like real customers: most pay, some don't. */
    private static ChainBuilder pay() {
        return randomSwitch().on(
                percent(80.0).then(
                        exec(pressOnProviderPage("approve")).exec(waitForOrder())),
                percent(10.0).then(
                        exec(pressOnProviderPage("decline")).exec(waitForOrder())),
                // The remaining 10% close the page and walk away. Their seats stay held until
                // reconciliation expires the order after its 15-minute payment deadline.
                percent(10.0).then(
                        exec(session -> session))
        );
    }

    // ---- The journey of ONE fan ----

    private final ScenarioBuilder buyers = scenario("Buyer")
            .feed(fans)
            .exec(getSeats())
            .doIf(session -> session.getBoolean("held")).then(
                    pause(Duration.ofSeconds(1), Duration.ofSeconds(3))
                            .exec(checkOut())
                            .pause(Duration.ofSeconds(2), Duration.ofSeconds(5))
                            .exec(pay())
            );

    private static int counter(Session session, String name) {
        return session.contains(name) ? session.getInt(name) : 0;
    }

    // ---- How MANY fans, and when ----

    {
        System.out.println("Testing event " + EVENT_ID + " on " + BASE_URL + ", provider " + PSP_URL);

        if (EVENT_ID == null) {
            throw new IllegalArgumentException("Pass the event to test with -DeventId=...");
        }

        setUp(buyers.injectOpen(
                constantUsersPerSec(USERS_PER_SEC).during(Duration.ofSeconds(DURATION_SECONDS))))
                .protocols(httpProtocol)
                .assertions(global().failedRequests().percent().lt(1.0));
    }
}