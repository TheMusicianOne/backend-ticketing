package io.github.themusicianone.ticketing_backend;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import io.github.themusicianone.ticketing_backend.auth.LoginRequest;
import io.github.themusicianone.ticketing_backend.auth.RegisterRequest;
import io.github.themusicianone.ticketing_backend.user.User;
import io.github.themusicianone.ticketing_backend.user.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.web.servlet.client.RestTestClient;

class AuthControllerTest extends IntegrationTest {

    private static final String VALID_PASSWORD = "correct-horse-battery";

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JwtDecoder jwtDecoder;

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

    @Test
    void registeredUserCanLogIn() {
        String email = uniqueEmail();
        register(new RegisterRequest(email, VALID_PASSWORD)).expectStatus().isCreated();

        loginRequest(new LoginRequest(email, VALID_PASSWORD))
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.accessToken").isNotEmpty()
                .jsonPath("$.tokenType").isEqualTo("Bearer")
                .jsonPath("$.expiresIn").isEqualTo(3600);
    }

    @Test
    void customerTokenCarriesIdentityAndCustomerRole() {
        String email = uniqueEmail();
        register(new RegisterRequest(email, VALID_PASSWORD)).expectStatus().isCreated();
        User user = userRepository.findByEmail(email).orElseThrow();

        Jwt jwt = jwtDecoder.decode(login(email, VALID_PASSWORD));

        assertThat(jwt.getSubject()).isEqualTo(user.getId().toString());
        assertThat(jwt.getClaimAsString("email")).isEqualTo(email);
        assertThat(jwt.getClaimAsStringList("roles")).containsExactly("CUSTOMER");
        assertThat(jwt.getClaimAsString("iss")).isEqualTo("ticketing-backend");
        assertThat(jwt.getExpiresAt()).isAfter(jwt.getIssuedAt());
    }

    @Test
    void adminTokenCarriesAdminRole() {
        Jwt jwt = jwtDecoder.decode(adminToken());

        assertThat(jwt.getClaimAsStringList("roles")).containsExactly("ADMIN");
    }

    @Test
    void loginEmailIsCaseInsensitive() {
        String email = uniqueEmail();
        register(new RegisterRequest(email, VALID_PASSWORD)).expectStatus().isCreated();

        loginRequest(new LoginRequest(email.toUpperCase(), VALID_PASSWORD))
                .expectStatus().isOk();
    }

    @Test
    void wrongPasswordIsRejected() {
        String email = uniqueEmail();
        register(new RegisterRequest(email, VALID_PASSWORD)).expectStatus().isCreated();

        loginRequest(new LoginRequest(email, VALID_PASSWORD + "-wrong"))
                .expectStatus().isUnauthorized()
                .expectBody()
                .jsonPath("$.accessToken").doesNotExist();
    }

    @Test
    void unknownEmailAndWrongPasswordAreIndistinguishable() {
        String email = uniqueEmail();
        register(new RegisterRequest(email, VALID_PASSWORD)).expectStatus().isCreated();

        // Same status and same message, so the API does not reveal which emails are registered
        loginRequest(new LoginRequest(email, "wrong-password-123"))
                .expectStatus().isUnauthorized()
                .expectBody()
                .jsonPath("$.detail").isEqualTo("Invalid email or password");

        loginRequest(new LoginRequest(uniqueEmail(), "wrong-password-123"))
                .expectStatus().isUnauthorized()
                .expectBody()
                .jsonPath("$.detail").isEqualTo("Invalid email or password");
    }

    @Test
    void invalidLoginRequestsAreRejected() {
        loginRequest(new LoginRequest("", VALID_PASSWORD))
                .expectStatus().isBadRequest();

        loginRequest(new LoginRequest("not-an-email", VALID_PASSWORD))
                .expectStatus().isBadRequest();

        loginRequest(new LoginRequest(uniqueEmail(), ""))
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.errors.password").exists();
    }

    private RestTestClient.ResponseSpec loginRequest(LoginRequest request) {
        return client.post()
                .uri("/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .body(request)
                .exchange();
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