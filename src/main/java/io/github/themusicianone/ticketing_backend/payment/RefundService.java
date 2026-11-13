package io.github.themusicianone.ticketing_backend.payment;


import io.github.themusicianone.ticketing_backend.order.Order;
import io.github.themusicianone.ticketing_backend.order.OrderRepository;
import io.github.themusicianone.ticketing_backend.order.OrderStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

@Service
public class RefundService {
    private static final int BATCH_SIZE = 50;
    private final OrderRepository orderRepository;
    private final PaymentGateway paymentGateway;
    private static final Logger log = LoggerFactory.getLogger(RefundService.class);

    public RefundService(OrderRepository orderRepository, PaymentGateway paymentGateway) {
        this.orderRepository = orderRepository;
        this.paymentGateway = paymentGateway;
    }

    public int processRefunds(){
        List<Order> refundsList = orderRepository.findRefundsToProcess(PageRequest.of(0,BATCH_SIZE));
        int refundProcessed = 0;
        for (Order order : refundsList) {
            try {
                paymentGateway.refund(order.getPaymentId(), "refund-order-" + order.getId());
                int marked = orderRepository.markRefunded(order.getId(), Instant.now(), OrderStatus.REFUNDED);
                if (marked==1) {refundProcessed++;}
            }catch (RuntimeException e) {
                log.warn("Error processing refund for orderId: {}: {}",order.getId(),e.getMessage());
            }

        }
        return refundProcessed;
    }
}
