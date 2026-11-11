package io.github.themusicianone.ticketing_backend.hold;


import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;

@RestController
public class HoldController {
    private final HoldService holdService;
    public HoldController(HoldService holdService) {
        this.holdService = holdService;
    }


    @PostMapping("/events/{eventId}/holds")
    ResponseEntity<HoldResponse> createHold(
            @PathVariable Long eventId,
            @Valid @RequestBody CreateHoldRequest request,
            @AuthenticationPrincipal Jwt jwt,
            UriComponentsBuilder ucb){

        HoldResponse created = holdService.create(eventId,Long.valueOf(jwt.getSubject()),request);
        URI locationOfNewHold = ucb.path("/holds/{id}").buildAndExpand(created.id()).toUri();
        return ResponseEntity.created(locationOfNewHold).body(created);
    }


    @GetMapping("/holds/{holdId}")
    ResponseEntity<HoldResponse> getHold(@PathVariable Long holdId, @AuthenticationPrincipal Jwt jwt) {
        HoldResponse response = holdService.findForUser(holdId, Long.valueOf(jwt.getSubject()));
        return ResponseEntity.ok(response);
    }

    @DeleteMapping("/holds/{holdId}")
    ResponseEntity<Void> releaseHold(@PathVariable Long holdId, @AuthenticationPrincipal Jwt jwt) {
        holdService.release(holdId, Long.valueOf(jwt.getSubject()));
        return ResponseEntity.noContent().build();
    }

}
