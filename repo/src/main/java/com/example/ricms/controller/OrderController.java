package com.example.ricms.controller;

import com.example.ricms.dto.request.AddNoteRequest;
import com.example.ricms.dto.request.AttachmentDto;
import com.example.ricms.dto.request.ConfirmPaymentRequest;
import com.example.ricms.dto.request.PlaceOrderRequest;
import com.example.ricms.dto.response.AttachmentResponse;
import com.example.ricms.dto.response.OrderPlacementResult;
import com.example.ricms.dto.response.OrderResponse;
import com.example.ricms.dto.response.PageResponse;
import com.example.ricms.security.SecurityUtils;
import com.example.ricms.service.OrderService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/v1/orders")
@RequiredArgsConstructor
public class OrderController {

    private final OrderService orderService;

    @PostMapping
    public ResponseEntity<OrderResponse> placeOrder(
            @Valid @RequestBody PlaceOrderRequest request,
            @RequestHeader("Idempotency-Key") String idempotencyKey) {
        OrderPlacementResult result = orderService.placeOrder(request, idempotencyKey);
        // C) 201 for new orders; 200 for idempotency replays within the 10-min window
        HttpStatus status = result.replay() ? HttpStatus.OK : HttpStatus.CREATED;
        return ResponseEntity.status(status).body(result.order());
    }

    @GetMapping
    public ResponseEntity<PageResponse<OrderResponse>> listOrders(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int pageSize,
            @RequestParam(required = false) UUID buyerId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String orderNumber) {
        return ResponseEntity.ok(orderService.getOrders(page, pageSize, buyerId, status, orderNumber));
    }

    @GetMapping("/{orderId}")
    public ResponseEntity<OrderResponse> getOrder(@PathVariable UUID orderId) {
        return ResponseEntity.ok(orderService.getOrder(orderId));
    }

    @PostMapping("/{orderId}/confirm-payment")
    public ResponseEntity<OrderResponse> confirmPayment(
            @PathVariable UUID orderId,
            @RequestBody ConfirmPaymentRequest request) {
        return ResponseEntity.ok(orderService.confirmPayment(orderId, request.getPaymentReference()));
    }

    @PostMapping("/{orderId}/fulfillment")
    public ResponseEntity<OrderResponse> fulfillOrder(@PathVariable UUID orderId) {
        return ResponseEntity.ok(orderService.fulfillOrder(orderId));
    }

    @PostMapping("/{orderId}/confirm-delivery")
    public ResponseEntity<OrderResponse> confirmDelivery(@PathVariable UUID orderId) {
        return ResponseEntity.ok(orderService.confirmDelivery(orderId));
    }

    @PostMapping("/{orderId}/complete")
    public ResponseEntity<OrderResponse> completeOrder(@PathVariable UUID orderId) {
        return ResponseEntity.ok(orderService.completeOrder(orderId));
    }

    @PostMapping("/{orderId}/notes")
    public ResponseEntity<Void> addNote(
            @PathVariable UUID orderId,
            @Valid @RequestBody AddNoteRequest request) {
        orderService.addNote(orderId, request.getNote(), SecurityUtils.getCurrentUserId());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{orderId}/attachments")
    public ResponseEntity<AttachmentResponse> addAttachment(
            @PathVariable UUID orderId,
            @Valid @RequestBody AttachmentDto request) {
        AttachmentResponse response = orderService.addAttachment(
                orderId, request.getBlobRef(), request.getContentType(),
                SecurityUtils.getCurrentUserId());
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }
}
