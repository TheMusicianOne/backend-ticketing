package io.github.themusicianone.ticketing_backend.user;


import io.github.themusicianone.ticketing_backend.event.EventResponse;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;
@Service
public class UserService {
    private final UserRepository userRepository;
    public UserService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    Optional<UserResponse> findById(Long id){
        Optional<User> user = userRepository.findById(id);

        if(user.isPresent()) {
            return Optional.of(UserResponse.from(user.get()));
        }
        throw new ResponseStatusException(HttpStatus.NOT_FOUND, String.format("User with id %s not found", id));

    }

}
