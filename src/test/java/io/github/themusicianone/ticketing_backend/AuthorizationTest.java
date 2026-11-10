package io.github.themusicianone.ticketing_backend;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;
import javax.crypto.spec.SecretKeySpec;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.test.web.servlet.client.RestTestClient;

/**
 * Verifies the access rules in SecurityConfig: who may call which endpoint,
 * and that tampered, foreign or expired tokens are rejected.
 */
class AuthorizationTest extends IntegrationTest {

    @Value("${app.jwt.secret}")
    private String jwtSecret;

    // ---- Admin-only endpoints ------------------------------------------------
    // Spring Security rejects the request before the controller runs,
    // so the event id and body don't need to be valid.

    @ParameterizedTest
    @ValueSource(strings = {"/venues", "/events", "/events/1/seats", "/events/1/seats/generate"})
    void adminEndpointsRequireAuthentication(String uri) {
        post(uri, null)
                .expectStatus().isUnauthorized()
                .expectHeader().value(HttpHeaders.WWW_AUTHENTICATE, header -> assertThat(header).startsWith("Bearer"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"/venues", "/events", "/events/1/seats", "/events/1/seats/generate"})
    void customersCannotUseAdminEndpoints(String uri) {
        post(uri, customerToken())
                .expectStatus().isForbidden();
    }

    @Test
    void adminCanUseAdminEndpoints() {
        post("/venues", adminToken(), """
                {"name":"Paradiso","city":"Amsterdam"}
                """)
                .expectStatus().isCreated();
    }

    // ---- Public endpoints ------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {"/events", "/venues/999999", "/events/999999/seats"})
    void readEndpointsArePublic(String uri) {
        client.get()
                .uri(uri)
                .exchange()
                .expectStatus().value(status -> {
                    if (status == 401 || status == 403) {
                        throw new AssertionError("Expected public access to " + uri + " but got " + status);
                    }
                });
    }

    @Test
    void healthEndpointIsPublic() {
        client.get()
                .uri("/actuator/health")
                .exchange()
                .expectStatus().isOk();
    }

    @Test
    void unmappedRoutesRequireAuthentication() {
        client.get()
                .uri("/does-not-exist")
                .exchange()
                .expectStatus().isUnauthorized();
    }

    // ---- Token validation ----------------------------------------------------

    @Test
    void malformedTokenIsRejected() {
        post("/venues", "not-a-jwt").expectStatus().isUnauthorized();
    }

    @Test
    void tokenSignedWithAnotherKeyIsRejected() {
        String forged = sign("a-completely-different-secret-of-at-least-32-bytes",
                List.of("ADMIN"), Instant.now().plus(1, ChronoUnit.HOURS));

        post("/venues", forged).expectStatus().isUnauthorized();
    }

    @Test
    void expiredTokenIsRejected() {
        // Beyond the decoder's default 60 second clock skew
        String expired = sign(jwtSecret, List.of("ADMIN"), Instant.now().minus(5, ChronoUnit.MINUTES));

        post("/venues", expired).expectStatus().isUnauthorized();
    }

    @Test
    void tamperedRoleClaimIsRejected() {
        // Take a real customer token and swap its payload for one claiming ADMIN,
        // keeping the original signature.
        String[] parts = customerToken().split("\\.");
        String payload = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8)
                .replace("\"CUSTOMER\"", "\"ADMIN\"");
        String tampered = parts[0] + "."
                + Base64.getUrlEncoder().withoutPadding()
                        .encodeToString(payload.getBytes(StandardCharsets.UTF_8))
                + "." + parts[2];

        post("/venues", tampered).expectStatus().isUnauthorized();
    }

    @Test
    void unsignedTokenIsRejected() {
        String header = base64Url("{\"alg\":\"none\",\"typ\":\"JWT\"}");
        String payload = base64Url("{\"sub\":\"1\",\"roles\":[\"ADMIN\"],\"exp\":"
                + Instant.now().plus(1, ChronoUnit.HOURS).getEpochSecond() + "}");

        post("/venues", header + "." + payload + ".").expectStatus().isUnauthorized();
    }

    // ---- Helpers -----------------------------------------------------------------

    private RestTestClient.ResponseSpec post(String uri, String token) {
        return post(uri, token, "{}");
    }

    private RestTestClient.ResponseSpec post(String uri, String token, String json) {
        return client.post()
                .uri(uri)
                .headers(h -> {
                    if (token != null) {
                        h.setBearerAuth(token);
                    }
                })
                .contentType(MediaType.APPLICATION_JSON)
                .body(json)
                .exchange();
    }

    private static String sign(String secret, List<String> roles, Instant expiresAt) {
        var key = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        var encoder = new NimbusJwtEncoder(new ImmutableSecret<>(key));

        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer("ticketing-backend")
                .issuedAt(expiresAt.minus(1, ChronoUnit.HOURS))
                .expiresAt(expiresAt)
                .subject("1")
                .claim("roles", roles)
                .build();

        return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
                .getTokenValue();
    }

    private static String base64Url(String json) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }
}
