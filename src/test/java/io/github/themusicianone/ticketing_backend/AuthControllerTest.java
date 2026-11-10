package io.github.themusicianone.ticketing_backend;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import io.github.themusicianone.ticketing_backend.TestcontainersConfiguration;
import io.github.themusicianone.ticketing_backend.auth.RegisterRequest;
import io.github.themusicianone.ticketing_backend.user.User;
import io.github.themusicianone.ticketing_backend.user.UserRepository;
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
class AuthControllerTest {

    private static final String VALID_PASSWORD = "correct-horse-battery";

    @Autowired
    private RestTestClient client;

    @Autowired
    private UserRepository userRepository;

    @Test
    void userCanRegister() {
        String email = uniqueEmail();

        register(new RegisterRequest(email, VALID_PASSWORD))
                .expectStatus().isCreated()
                .expectBody()
                .jsonPath("$.id").exists()
                .jsonPath("$.email").isEqualTo(email)
                .jsonPath("$.role").isEqualTo("CUSTOMER");
    }

    @Test
    void emailIsStoredInLowercase() {
        String email = uniqueEmail();

        register(new RegisterRequest("  " + email.toUpperCase() + "  ", VALID_PASSWORD))
                .expectStatus().isCreated()
                .expectBody()
                .jsonPath("$.email").isEqualTo(email);

        assertThat(userRepository.existsByEmail(email)).isTrue();
    }

    @Test
    void responseDoesNotContainPassword() {
        register(new RegisterRequest(uniqueEmail(), VALID_PASSWORD))
                .expectStatus().isCreated()
                .expectBody()
                .jsonPath("$.password").doesNotExist()
                .jsonPath("$.passwordHash").doesNotExist();
    }

    @Test
    void passwordIsStoredAsHash() {
        String email = uniqueEmail();

        register(new RegisterRequest(email, VALID_PASSWORD))
                .expectStatus().isCreated();

        User user = userRepository.findByEmail(email).orElseThrow();
        assertThat(user.getPasswordHash())
                .isNotEqualTo(VALID_PASSWORD)
                .startsWith("{bcrypt}");
    }

    @Test
    void duplicateEmailIsRejected() {
        String email = uniqueEmail();

        register(new RegisterRequest(email, VALID_PASSWORD))
                .expectStatus().isCreated();

        register(new RegisterRequest(email.toUpperCase(), VALID_PASSWORD))
                .expectStatus().isEqualTo(409);
    }

    @Test
    void clientCannotChooseAdminRole() {
        String email = uniqueEmail();
        String json = """
                {"email":"%s","password":"%s","role":"ADMIN"}
                """.formatted(email, VALID_PASSWORD);

        client.post()
                .uri("/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .body(json)
                .exchange()
                .expectStatus().isCreated()
                .expectBody()
                .jsonPath("$.role").isEqualTo("CUSTOMER");
    }

    @Test
    void invalidRegistrationsAreRejected() {
        register(new RegisterRequest("not-an-email", VALID_PASSWORD))
                .expectStatus().isBadRequest();

        register(new RegisterRequest("", VALID_PASSWORD))
                .expectStatus().isBadRequest();

        register(new RegisterRequest(uniqueEmail(), "too-short"))
                .expectStatus().isBadRequest();

        register(new RegisterRequest(uniqueEmail(), "x".repeat(73)))
                .expectStatus().isBadRequest();
    }

    @Test
    void validationErrorsNameTheField() {
        register(new RegisterRequest(uniqueEmail(), "too-short"))
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.errors.password").exists();
    }

    private RestTestClient.ResponseSpec register(RegisterRequest request) {
        return client.post()
                .uri("/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .body(request)
                .exchange();
    }

    private String uniqueEmail() {
        return "user-" + UUID.randomUUID() + "@example.com";
    }
}