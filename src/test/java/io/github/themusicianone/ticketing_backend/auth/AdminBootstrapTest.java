package io.github.themusicianone.ticketing_backend.auth;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.themusicianone.ticketing_backend.IntegrationTest;
import io.github.themusicianone.ticketing_backend.user.Role;
import io.github.themusicianone.ticketing_backend.user.User;
import io.github.themusicianone.ticketing_backend.user.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;

// Lives in the auth package because AdminBootstrap is package-private
class AdminBootstrapTest extends IntegrationTest {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Test
    void adminIsCreatedOnStartup() {
        User admin = userRepository.findByEmail(ADMIN_EMAIL).orElseThrow();

        assertThat(admin.getRole()).isEqualTo(Role.ADMIN);
        assertThat(admin.getPasswordHash())
                .isNotEqualTo(ADMIN_PASSWORD)
                .startsWith("{bcrypt}");
    }

    @Test
    void rerunningDoesNotDuplicateOrOverwriteAdmin() {
        long usersBefore = userRepository.count();
        String hashBefore = userRepository.findByEmail(ADMIN_EMAIL).orElseThrow().getPasswordHash();

        // Same admin with different casing and a new password, as after a config change and restart
        new AdminBootstrap(userRepository, passwordEncoder, "  " + ADMIN_EMAIL.toUpperCase() + " ", "a-new-password")
                .run(null);

        assertThat(userRepository.count()).isEqualTo(usersBefore);
        assertThat(userRepository.findByEmail(ADMIN_EMAIL).orElseThrow().getPasswordHash()).isEqualTo(hashBefore);
        login(ADMIN_EMAIL, ADMIN_PASSWORD);
    }

    @Test
    void blankConfigurationCreatesNoAdmin() {
        long usersBefore = userRepository.count();

        new AdminBootstrap(userRepository, passwordEncoder, "", "").run(null);
        new AdminBootstrap(userRepository, passwordEncoder, "someone@example.com", " ").run(null);

        assertThat(userRepository.count()).isEqualTo(usersBefore);
        assertThat(userRepository.existsByEmail("someone@example.com")).isFalse();
    }
}
