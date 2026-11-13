package io.github.themusicianone.ticketing_backend;

import java.util.UUID;

import io.github.themusicianone.ticketing_backend.auth.LoginRequest;
import io.github.themusicianone.ticketing_backend.auth.RegisterRequest;
import io.github.themusicianone.ticketing_backend.auth.TokenResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.client.RestTestClient;

@Import(TestcontainersConfiguration.class)
@AutoConfigureRestTestClient
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "app.admin.email=" + IntegrationTest.ADMIN_EMAIL,
                "app.admin.password=" + IntegrationTest.ADMIN_PASSWORD,
                "app.holds.expiry-job.enabled=false",
                "app.payments.provider=stub",
                "app.payments.reconciliation.enabled=false",
                "app.payments.refund.enabled=false"
        })
public abstract class IntegrationTest {

    protected static final String ADMIN_EMAIL = "admin@test.local";
    protected static final String ADMIN_PASSWORD = "test-admin-password";
    protected static final String CUSTOMER_PASSWORD = "customer-password";

    @Autowired
    protected RestTestClient client;

    protected String adminToken() {
        return login(ADMIN_EMAIL, ADMIN_PASSWORD);
    }

    protected String customerToken() {
        String email = "customer-" + UUID.randomUUID() + "@example.com";

        client.post()
                .uri("/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .body(new RegisterRequest(email, CUSTOMER_PASSWORD))
                .exchange()
                .expectStatus().isCreated();

        return login(email, CUSTOMER_PASSWORD);
    }

    protected String login(String email, String password) {
        TokenResponse response = client.post()
                .uri("/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .body(new LoginRequest(email, password))
                .exchange()
                .expectStatus().isOk()
                .expectBody(TokenResponse.class)
                .returnResult()
                .getResponseBody();

        return response.accessToken();
    }
}