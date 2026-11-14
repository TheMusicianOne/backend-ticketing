package io.github.themusicianone.ticketing_backend.order;


import io.github.themusicianone.ticketing_backend.common.PageResponse;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;
import java.util.List;
@RestController
@RequestMapping("/orders")
public class OrderController {

    private final OrderService orderService;
    public OrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    @GetMapping("/{orderId}")
    public ResponseEntity<OrderResponse> getOrder(@PathVariable long orderId, @AuthenticationPrincipal Jwt jwt ){
        Optional<OrderResponse> response  = orderService.findForUser(orderId,Long.valueOf(jwt.getSubject()));
        if(response.isPresent()) {
            return ResponseEntity.ok(response.get());
        }
        throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Order not found");
    }

    @GetMapping
    PageResponse<OrderResponse> getOrders(@PageableDefault(size=20,sort="id",direction = Sort.Direction.DESC) Pageable pageable, @AuthenticationPrincipal Jwt jwt ){
        return orderService.findAllForUser(Long.valueOf(jwt.getSubject()),pageable);
    }

    @GetMapping("/{orderId}/tickets")
    List<TicketResponse> getTickets(@PathVariable long orderId, @AuthenticationPrincipal Jwt jwt) {
        return orderService.findTicketsForUser(orderId, Long.valueOf(jwt.getSubject()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Order not found"));
    }

}
