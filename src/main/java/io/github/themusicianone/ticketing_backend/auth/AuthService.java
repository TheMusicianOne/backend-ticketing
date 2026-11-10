package io.github.themusicianone.ticketing_backend.auth;


import io.github.themusicianone.ticketing_backend.user.User;
import io.github.themusicianone.ticketing_backend.user.UserRepository;
import io.github.themusicianone.ticketing_backend.user.UserResponse;
import org.apache.coyote.Response;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class AuthService {
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    public AuthService(UserRepository userRepository, PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Transactional
    public UserResponse register(RegisterRequest request) {
        String email = request.email();

        if (userRepository.existsByEmail(email)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Email already in use");
        }

        String passwordHash = passwordEncoder.encode(request.password());
        User savedUser = userRepository.save(new User(email, passwordHash));
        return UserResponse.from(savedUser);
    }
}
