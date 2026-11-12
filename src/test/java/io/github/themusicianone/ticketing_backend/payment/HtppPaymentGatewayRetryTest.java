package io.github.themusicianone.ticketing_backend.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.themusicianone.ticketing_backend.IntegrationTest;
import io.github.themusicianone.ticketing_backend.payment.PaymentGateway.CreatedPayment;
import io.github.themusicianone.ticketing_backend.payment.PaymentGateway.PaymentRequest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestClientException;

/**
 * Talks to a tiny fake provider built on the JDK's own HTTP server.
 * Each test scripts its answers ("fail, then succeed") and checks how many
 * requests arrived, and what they carried.
 */
class HttpPaymentGatewayRetryTest extends IntegrationTest {

    private static final String API_KEY = "test_retry_api_key";
    private static final String PAYMENT_JSON =
            "{\"id\":\"tr_test\",\"status\":\"open\",\"checkoutUrl\":\"http://pay.test/checkout/tr_test\"}";

    /** The answers the fake provider gives, in order. When it runs out, it answers 500. */
    private static final Queue<Answer> answers = new ConcurrentLinkedQueue<>();

    /** Every request the fake provider received. */
    private static final List<ReceivedRequest> received = new CopyOnWriteArrayList<>();

    private static final HttpServer server = startFakeProvider();

    @Autowired
    private PaymentGateway gateway;

    @DynamicPropertySource
    static void useFakeProvider(DynamicPropertyRegistry registry) {
        registry.add("app.payments.provider", () -> "http");
        registry.add("app.payments.base-url", () -> "http://localhost:" + server.getAddress().getPort());
        registry.add("app.payments.api-key", () -> API_KEY);
        registry.add("app.payments.read-timeout", () -> "500ms");
        registry.add("app.payments.connect-timeout", () -> "1s");
    }

    @BeforeEach
    void reset() {
        answers.clear();
        received.clear();
    }

    @AfterAll
    static void stopFakeProvider() {
        server.stop(0);
    }

    // ---- Retried failures --------------------------------------------------------------

    @Test
    void temporaryServerErrorIsRetried() {
        answers.add(Answer.status(503));
        answers.add(Answer.created());

        CreatedPayment payment = gateway.createPayment(request());

        assertThat(payment.paymentId()).isEqualTo("tr_test");
        assertThat(payment.checkoutUrl()).isEqualTo("http://pay.test/checkout/tr_test");
        assertThat(received).hasSize(2);
    }

    @Test
    void slowResponseIsRetried() {
        // Longer than the 500ms read timeout, then a normal answer
        answers.add(Answer.created().after(1500));
        answers.add(Answer.created());

        CreatedPayment payment = gateway.createPayment(request());

        assertThat(payment.paymentId()).isEqualTo("tr_test");
        assertThat(received).hasSize(2);
    }

    @Test
    void lostResponseIsRecoveredByTheRetry() {
        // The provider created the payment but the answer got lost, so the retry gets it back with 200
        answers.add(Answer.status(503));
        answers.add(Answer.ok());

        CreatedPayment payment = gateway.createPayment(request());

        assertThat(payment.paymentId()).isEqualTo("tr_test");
    }

    // ---- Giving up -------------------------------------------------------------------

    @Test
    void persistentServerErrorGivesUpAfterThreeAttempts() {
        answers.add(Answer.status(500));
        answers.add(Answer.status(500));
        answers.add(Answer.status(500));

        assertThatThrownBy(() -> gateway.createPayment(request()))
                .isInstanceOf(HttpServerErrorException.class);

        assertThat(received).hasSize(3);
    }

    @Test
    void persistentTimeoutGivesUpAfterThreeAttempts() {
        answers.add(Answer.created().after(1500));
        answers.add(Answer.created().after(1500));
        answers.add(Answer.created().after(1500));

        assertThatThrownBy(() -> gateway.createPayment(request()))
                .isInstanceOf(RestClientException.class);

        assertThat(received).hasSize(3);
    }

    @Test
    void clientErrorIsNotRetried() {
        answers.add(Answer.status(400));

        assertThatThrownBy(() -> gateway.createPayment(request()))
                .isInstanceOf(HttpClientErrorException.class);

        assertThat(received).hasSize(1);
    }

    @Test
    void idempotencyConflictIsNotRetried() {
        answers.add(Answer.status(422));

        assertThatThrownBy(() -> gateway.createPayment(request()))
                .isInstanceOf(HttpClientErrorException.class);

        assertThat(received).hasSize(1);
    }

    // ---- What each attempt carries ------------------------------------------------------

    @Test
    void everyAttemptSendsTheSameIdempotencyKeyAndTheApiKey() {
        answers.add(Answer.status(503));
        answers.add(Answer.status(503));
        answers.add(Answer.created());

        gateway.createPayment(new PaymentRequest("order-42", 9100, "Order 42"));

        assertThat(received).hasSize(3);
        assertThat(received).allSatisfy(request -> {
            assertThat(request.idempotencyKey()).isEqualTo("order-42");
            assertThat(request.authorization()).isEqualTo("Bearer " + API_KEY);
        });
    }

    @Test
    void requestBodyContainsTheAmountInCamelCase() {
        answers.add(Answer.created());

        gateway.createPayment(new PaymentRequest("order-7", 4550, "Order 7"));

        String body = received.get(0).body();
        assertThat(body).contains("\"amountCents\":4550");
        assertThat(body).contains("\"currency\":\"EUR\"");
        assertThat(body).contains("\"description\":\"Order 7\"");
    }

    // ---- The fake provider -------------------------------------------------------------

    private static PaymentRequest request() {
        return new PaymentRequest("order-1", 5000, "Order 1");
    }

    private record Answer(int status, String body, long delayMillis) {

        static Answer created() {
            return new Answer(201, PAYMENT_JSON, 0);
        }

        static Answer ok() {
            return new Answer(200, PAYMENT_JSON, 0);
        }

        static Answer status(int status) {
            return new Answer(status, "{\"detail\":\"Simulated\"}", 0);
        }

        Answer after(long delayMillis) {
            return new Answer(status, body, delayMillis);
        }
    }

    private record ReceivedRequest(String idempotencyKey, String authorization, String body) {
    }

    private static HttpServer startFakeProvider() {
        try {
            HttpServer httpServer = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            httpServer.createContext("/v1/payments", HttpPaymentGatewayRetryTest::answer);
            // Each request on its own thread, so a slow answer doesn't block the retry behind it
            httpServer.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
            httpServer.start();
            return httpServer;
        } catch (IOException e) {
            throw new IllegalStateException("Could not start the fake provider", e);
        }
    }

    private static void answer(HttpExchange exchange) throws IOException {
        try (exchange) {
            String requestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            received.add(new ReceivedRequest(
                    exchange.getRequestHeaders().getFirst("Idempotency-Key"),
                    exchange.getRequestHeaders().getFirst("Authorization"),
                    requestBody));

            Answer answer = answers.poll();
            if (answer == null) {
                answer = Answer.status(500);
            }

            if (answer.delayMillis() > 0) {
                Thread.sleep(answer.delayMillis());
            }

            byte[] responseBody = answer.body().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(answer.status(), responseBody.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(responseBody);
            }
        } catch (IOException e) {
            // The client gave up waiting (a timeout), so there's nobody left to answer
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}