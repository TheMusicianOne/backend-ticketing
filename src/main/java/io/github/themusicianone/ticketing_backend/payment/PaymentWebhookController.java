package io.github.themusicianone.ticketing_backend.payment;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.core.JacksonException;
import java.time.Instant;

@RestController
class PaymentWebhookController {

    // inject WebhookSignatureVerifier, PaymentWebhookService, and JsonMapper

    private final WebHookSignatureVerifier verifier;
    private final PaymentWebhookService webhookService;
    private final JsonMapper jsonMapper;

    public PaymentWebhookController(WebHookSignatureVerifier verifier, PaymentWebhookService webhookService, JsonMapper jsonMapper) {
        this.verifier = verifier;
        this.webhookService = webhookService;
        this.jsonMapper = jsonMapper;
    }

    @PostMapping("/webhooks/payments")
    ResponseEntity<Void> receive(
            @RequestHeader(name = "Payment-Signature", required = false) String signature,
            @RequestBody byte[] body) {

        if (!verifier.isValid(signature, body, Instant.now())) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        PaymentWebhookService.PaymentEvent event;
        try {
            event = jsonMapper.readValue(body, PaymentWebhookService.PaymentEvent.class);
        } catch (JacksonException e) {
            return ResponseEntity.badRequest().build();
        }

        webhookService.handle(event);
        return ResponseEntity.ok().build();
    }
}
