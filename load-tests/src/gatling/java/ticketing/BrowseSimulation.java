package ticketing;

import static io.gatling.javaapi.core.CoreDsl.*;
import static io.gatling.javaapi.http.HttpDsl.*;

import java.time.Duration;

import io.gatling.javaapi.core.ScenarioBuilder;
import io.gatling.javaapi.core.Simulation;
import io.gatling.javaapi.http.HttpProtocolBuilder;

public class BrowseSimulation extends Simulation {

    private static final String BASE_URL = System.getProperty("baseUrl", "http://localhost:8080");

    private final HttpProtocolBuilder httpProtocol = http
            .baseUrl(BASE_URL)
            .acceptHeader("application/json");

    // What ONE simulated fan does
    private final ScenarioBuilder browse = scenario("Browse events")
            .exec(http("List events")
                    .get("/events?size=20")
                    .check(status().is(200))
                    .check(jsonPath("$.content[0].id").saveAs("eventId")))
            .pause(Duration.ofMillis(500), Duration.ofSeconds(2))
            .exec(http("Event details")
                    .get("/events/#{eventId}")
                    .check(status().is(200)))
            .pause(Duration.ofMillis(500), Duration.ofSeconds(2))
            .exec(http("Available seats")
                    .get("/events/#{eventId}/seats?status=AVAILABLE&size=50")
                    .check(status().is(200)));

    {
        // How MANY fans arrive, and when
        setUp(browse.injectOpen(
                rampUsersPerSec(1).to(20).during(Duration.ofSeconds(60))))
                .protocols(httpProtocol)
                .assertions(
                        global().failedRequests().percent().lt(1.0),
                        global().responseTime().percentile(95.0).lt(500));
    }
}