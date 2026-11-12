package io.github.themusicianone.ticketing_backend.order;


import io.github.themusicianone.ticketing_backend.hold.Hold;
import io.github.themusicianone.ticketing_backend.hold.HoldRepository;
import io.github.themusicianone.ticketing_backend.seat.SeatStatus;
import io.github.themusicianone.ticketing_backend.hold.HoldStatus;
import io.github.themusicianone.ticketing_backend.seat.Seat;
import io.github.themusicianone.ticketing_backend.seat.SeatRepository;
import io.github.themusicianone.ticketing_backend.user.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;


import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Optional;

@Service
public class OrderService {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final OrderRepository orderRepository;
    private final HoldRepository holdRepository;
    private final SeatRepository seatRepository;
    private final TicketRepository ticketRepository;
    private final UserRepository userRepository;

    public OrderService(OrderRepository orderRepository, HoldRepository holdRepository, SeatRepository seatRepository, TicketRepository ticketRepository, UserRepository userRepository
    ) {
        this.orderRepository = orderRepository;
        this.holdRepository = holdRepository;
        this.seatRepository = seatRepository;
        this.ticketRepository = ticketRepository;
        this.userRepository = userRepository;
    }

    @Transactional
    public ConfirmResult confirmOrder(Long orderId){
        Optional<Order> opOrder = orderRepository.findById(orderId);
        if (!opOrder.isPresent()) {
            throw new IllegalArgumentException("Unknown order" + orderId);
        }
        Order order = opOrder.get();
        Long holdId = order.getHold().getId();

        int confirmed = orderRepository.finishPending(orderId, OrderStatus.CONFIRMED,OrderStatus.PENDING, Instant.now());
        if(confirmed == 0) {
            return order.getStatus() == OrderStatus.CONFIRMED ? ConfirmResult.ALREADY_CONFIRMED : ConfirmResult.NEEDS_REFUND;

        }

        holdRepository.changeStatus(holdId, HoldStatus.PAYMENT_PENDING, HoldStatus.CONVERTED);
        List<Seat> seats = seatRepository.findByHold_IdOrderById((holdId));

        int sold = seatRepository.markSold(holdId, SeatStatus.HELD, SeatStatus.SOLD);
        if (sold != seats.size()) {
            throw new IllegalStateException("Order " + orderId + " expected "
                    + seats.size() + " held seats but sold " + sold);
        }

        Order managedOrder  = orderRepository.getReferenceById(orderId);
        List<Ticket> tickets = seats.stream().map(seat-> new Ticket(managedOrder,seat,newTicketCode())).toList();
        ticketRepository.saveAll(tickets);
        return ConfirmResult.CONFIRMED;

    }

    private static String newTicketCode() {
        byte[] bytes = new byte[16];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    @Transactional
    public void failOrder(Long orderId, OrderStatus outcome) {
        if (outcome != OrderStatus.FAILED && outcome != OrderStatus.EXPIRED) {
            throw new IllegalArgumentException("Cannot fail an order with outcome " + outcome);
        }

        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown order " + orderId));
        Long holdId = order.getHold().getId();

        int finished = orderRepository.finishPending(
                orderId, outcome, OrderStatus.PENDING, Instant.now());
        if (finished == 0) {
            return; // already finished by a webhook or the job, so nothing to do
        }

        holdRepository.changeStatus(holdId, HoldStatus.PAYMENT_PENDING, HoldStatus.RELEASED);
        seatRepository.releaseSeats(holdId, SeatStatus.AVAILABLE, SeatStatus.HELD);
    }

    private static final Duration PAYMENT_WINDOW = Duration.ofMinutes(15);

    @Transactional
    public Order startCheckout(Long holdId, Long userId, String idempotencyKey) {
        Instant now = Instant.now();

        int frozen = holdRepository.startPayment(
                holdId, userId, HoldStatus.ACTIVE, HoldStatus.PAYMENT_PENDING, now);
        if (frozen == 0) {
            if (holdRepository.findByIdAndUser_Id(holdId, userId).isEmpty()) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Hold does not exist");
            }
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Hold is not active or has expired");
        }

        Hold hold = holdRepository.getReferenceById(holdId);
        long total = seatRepository.totalPriceForHold(holdId);

        Order order = new Order(
                userRepository.getReferenceById(userId),
                hold.getEvent(),
                hold,
                Math.toIntExact(total),
                idempotencyKey,
                now.plus(PAYMENT_WINDOW));

        return orderRepository.save(order);
    }

    @Transactional
    public Order attachPayment(Long orderId, String paymentId, String checkoutUrl) {
        Order order = orderRepository.findById(orderId).orElseThrow();
        order.attachPayment(paymentId, checkoutUrl);
        return order;
    }


}
