package io.github.themusicianone.ticketing_backend.user;

import java.util.Locale;
import java.util.UUID;

import io.github.themusicianone.ticketing_backend.IntegrationTest;
import io.github.themusicianone.ticketing_backend.auth.RegisterRequest;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

class UserControllerTest extends IntegrationTest {

    @Test
    void customerSeesOwnDetails() {
        String email = uniqueEmail();
        String token = registerAndLogin(email);

        client.get()
                .uri("/users/me")
                .headers(h -> h.setBearerAuth(token))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.id").exists()
                .jsonPath("$.email").isEqualTo(email)
                .jsonPath("$.role").isEqualTo("CUSTOMER")
                .jsonPath("$.passwordHash").doesNotExist();
    }

    @Test
    void adminSeesAdminRole() {
        String token = adminToken();

        client.get()
                .uri("/users/me")
                .headers(h -> h.setBearerAuth(token))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.email").isEqualTo(ADMIN_EMAIL)
                .jsonPath("$.role").isEqualTo("ADMIN");
    }

    @Test
    void eachCustomerSeesOnlyTheirOwnDetails() {
        String firstEmail = uniqueEmail();
        String secondEmail = uniqueEmail();
        String firstToken = registerAndLogin(firstEmail);
        String secondToken = registerAndLogin(secondEmail);

        client.get()
                .uri("/users/me")
                .headers(h -> h.setBearerAuth(firstToken))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.email").isEqualTo(firstEmail);

        client.get()
                .uri("/users/me")
                .headers(h -> h.setBearerAuth(secondToken))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.email").isEqualTo(secondEmail);
    }

    @Test
    void requestWithoutTokenIsRejected() {
        client.get()
                .uri("/users/me")
                .exchange()
                .expectStatus().isUnauthorized();
    }

    private String registerAndLogin(String email) {
        client.post()
                .uri("/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .body(new RegisterRequest(email, CUSTOMER_PASSWORD))
                .exchange()
                .expectStatus().isCreated();

        return login(email, CUSTOMER_PASSWORD);
    }

    private String uniqueEmail() {
        return ("user-" + UUID.randomUUID() + "@example.com").toLowerCase(Locale.ROOT);
    }
}