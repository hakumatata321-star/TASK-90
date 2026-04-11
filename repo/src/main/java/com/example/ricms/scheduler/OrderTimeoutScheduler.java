package com.example.ricms.scheduler;

import com.example.ricms.service.OrderService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class OrderTimeoutScheduler {

    private final OrderService orderService;

    @Scheduled(fixedDelay = 60000)
    public void checkTimedOutOrders() {
        log.debug("Checking for timed-out orders...");
        orderService.closeTimedOutOrders();
    }
}
