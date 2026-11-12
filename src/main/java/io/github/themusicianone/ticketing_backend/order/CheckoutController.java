package io.github.themusicianone.ticketing_backend.order;


import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class CheckoutController {

    private final CheckoutService checkoutService;

    public CheckoutController(CheckoutService checkoutService) {
        this.checkoutService = checkoutService;
    }

    @PostMapping("/holds/{holdId}/checkout")
    ResponseEntity<OrderResponse> checkout(
            @PathVariable Long holdId,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 100) String idempotencyKey,
            @AuthenticationPrincipal Jwt jwt) {

        OrderResponse response = checkoutService.checkout(holdId,Long.valueOf(jwt.getSubject()),idempotencyKey);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);

    }
}
