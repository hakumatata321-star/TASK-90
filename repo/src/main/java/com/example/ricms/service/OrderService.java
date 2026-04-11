package com.example.ricms.service;

import com.example.ricms.domain.entity.*;
import com.example.ricms.domain.enums.OrderStatus;
import com.example.ricms.dto.request.PlaceOrderRequest;
import com.example.ricms.dto.response.AttachmentResponse;
import com.example.ricms.dto.response.OrderItemResponse;
import com.example.ricms.dto.response.OrderPlacementResult;
import com.example.ricms.dto.response.OrderResponse;
import com.example.ricms.dto.response.PageResponse;
import com.example.ricms.dto.response.PricingResult;
import com.example.ricms.exception.AppException;
import com.example.ricms.repository.*;
import com.example.ricms.security.PermissionEnforcer;
import com.example.ricms.security.SecurityUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class OrderService {

    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;
    private final OrderNoteRepository orderNoteRepository;
    private final InventoryRepository inventoryRepository;
    private final AttachmentRepository attachmentRepository;
    private final MemberRepository memberRepository;
    private final MemberService memberService;
    private final PricingEngine pricingEngine;
    private final AuditService auditService;
    private final PermissionEnforcer permissionEnforcer;

    private static final AtomicLong orderSeq = new AtomicLong(System.currentTimeMillis() % 100000);

    // -----------------------------------------------------------------------
    // A) Object-level authorization helper (IDOR prevention)
    // Non-admin callers must own the order. forWrite=true requires ADMIN:WRITE
    // for the admin bypass; forWrite=false requires ADMIN:READ.
    // -----------------------------------------------------------------------
    private void assertOrderOwnership(Order order, boolean forWrite) {
        UUID callerId = SecurityUtils.getCurrentUserId();
        boolean isOwner = callerId.equals(order.getBuyerUserId());
        String adminOp = forWrite ? "WRITE" : "READ";
        if (!isOwner && !SecurityUtils.hasPermission("ADMIN", adminOp)) {
            throw new AppException("FORBIDDEN",
                    "You do not have access to this order", HttpStatus.FORBIDDEN);
        }
    }

    @Transactional
    public OrderPlacementResult placeOrder(PlaceOrderRequest request, String idempotencyKey) {
        permissionEnforcer.require("ORDER", "WRITE");
        // C) Time-windowed idempotency — DB unique constraint was dropped in V6.
        //    Return replay=true (HTTP 200) if the same key was used within 10 min.
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            String keyHash = hashIdempotencyKey(request.getBuyerId(), idempotencyKey);
            Optional<Order> existing = orderRepository.findTopByBuyerUserIdAndIdempotencyKeyHashOrderByCreatedAtDesc(
                    request.getBuyerId(), keyHash);
            if (existing.isPresent()) {
                Order existingOrder = existing.get();
                if (existingOrder.getCreatedAt().isAfter(OffsetDateTime.now().minusMinutes(10))) {
                    return new OrderPlacementResult(toResponse(existingOrder), true);
                }
            }
        }

        // Compute subtotal
        BigDecimal subtotal = request.getItems().stream()
                .map(item -> item.getUnitPrice().multiply(BigDecimal.valueOf(item.getQuantity())))
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal shippingCost = computeShipping(request.getShippingCountry());

        // Pricing
        PricingResult pricing = pricingEngine.computePricing(
                request.getBuyerId(), subtotal, shippingCost,
                request.getCouponCode(), request.getCampaignId());

        // All-or-nothing inventory reservation (Q9):
        // Pass 1 — acquire pessimistic locks and verify ALL items are available before
        //           touching any reserved count. Use a LinkedHashMap to preserve order
        //           and deduplicate SKUs (aggregate quantities for duplicate SKU entries).
        Map<String, Integer> skuQuantities = new LinkedHashMap<>();
        for (var item : request.getItems()) {
            skuQuantities.merge(item.getSku(), item.getQuantity(), Integer::sum);
        }

        Map<String, Inventory> lockedInventory = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> entry : skuQuantities.entrySet()) {
            String sku = entry.getKey();
            int qty     = entry.getValue();
            Inventory inv = inventoryRepository.findBySkuWithLock(sku)
                    .orElseThrow(() -> new AppException("INVENTORY_NOT_FOUND",
                            "SKU not found: " + sku, HttpStatus.NOT_FOUND));
            int available = inv.getOnHand() - inv.getReserved();
            if (available < qty) {
                throw new AppException("INSUFFICIENT_INVENTORY",
                        "Insufficient inventory for SKU " + sku +
                        ": available=" + available + " requested=" + qty,
                        HttpStatus.UNPROCESSABLE_ENTITY);
            }
            lockedInventory.put(sku, inv);
        }

        // Pass 2 — all checks passed; reserve every SKU atomically within this TX.
        OffsetDateTime reservedUntil = OffsetDateTime.now().plusMinutes(30);
        for (Map.Entry<String, Integer> entry : skuQuantities.entrySet()) {
            Inventory inv = lockedInventory.get(entry.getKey());
            inv.setReserved(inv.getReserved() + entry.getValue());
            inv.setReservedUntil(reservedUntil);
            inventoryRepository.save(inv);
        }
        auditService.record(request.getBuyerId(), "INVENTORY", null,
                "INVENTORY_RESERVE", "USER_ACTION",
                null, Map.of("skus", skuQuantities));

        // Create order
        String orderNumber = "ORD-" + System.currentTimeMillis() + "-" + orderSeq.incrementAndGet();
        String idempotencyKeyHash = idempotencyKey != null ?
                hashIdempotencyKey(request.getBuyerId(), idempotencyKey) : null;

        Order order = Order.builder()
                .orderNumber(orderNumber)
                .buyerUserId(request.getBuyerId())
                .status(OrderStatus.PLACED)
                .subtotal(subtotal)
                .discountsTotal(pricing.getDiscountsTotal())
                .shippingTotal(pricing.getShippingTotal())
                .totalPayable(pricing.getTotalPayable())
                .couponId(pricing.getAppliedCouponId())
                .campaignId(pricing.getAppliedCampaignId())
                .paymentMethod(request.getPaymentMethod())
                .shippingCountry(request.getShippingCountry())
                .shippingPostalCode(request.getShippingPostalCode())
                .idempotencyKeyHash(idempotencyKeyHash)
                .items(new ArrayList<>())
                .build();

        order = orderRepository.save(order);

        // Create items
        List<OrderItem> items = new ArrayList<>();
        for (var itemDto : request.getItems()) {
            OrderItem item = OrderItem.builder()
                    .orderId(order.getId())
                    .sku(itemDto.getSku())
                    .quantity(itemDto.getQuantity())
                    .unitPrice(itemDto.getUnitPrice())
                    .lineTotal(itemDto.getUnitPrice().multiply(BigDecimal.valueOf(itemDto.getQuantity())))
                    .build();
            items.add(orderItemRepository.save(item));
        }
        order.setItems(items);

        auditService.record(request.getBuyerId(), "ORDER", order.getId().toString(),
                "PLACE_ORDER", "USER_ACTION", null, orderNumber);

        return new OrderPlacementResult(toResponse(order), false);
    }

    public PageResponse<OrderResponse> getOrders(int page, int pageSize, UUID buyerId,
                                                  String statusStr, String orderNumber) {
        permissionEnforcer.require("ORDER", "READ");
        // RLS: callers without ADMIN:READ can only see their own orders
        if (!SecurityUtils.hasPermission("ADMIN", "READ")) {
            buyerId = SecurityUtils.getCurrentUserId();
        }
        OrderStatus status = null;
        if (statusStr != null && !statusStr.isBlank()) {
            try {
                status = OrderStatus.valueOf(statusStr);
            } catch (Exception e) {
                throw new AppException("INVALID_STATUS", "Invalid order status: " + statusStr, HttpStatus.BAD_REQUEST);
            }
        }
        Page<Order> orders = orderRepository.findByBuyerUserIdWithFilters(
                buyerId, status, orderNumber, PageRequest.of(page, pageSize));
        return PageResponse.of(orders.map(this::toResponse));
    }

    public OrderResponse getOrder(UUID orderId) {
        permissionEnforcer.require("ORDER", "READ");
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new AppException("ORDER_NOT_FOUND", "Order not found", HttpStatus.NOT_FOUND));
        assertOrderOwnership(order, false);
        return toResponse(order);
    }

    @Transactional
    public OrderResponse confirmPayment(UUID orderId, String paymentRef) {
        permissionEnforcer.require("ORDER", "WRITE");
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new AppException("ORDER_NOT_FOUND", "Order not found", HttpStatus.NOT_FOUND));
        assertOrderOwnership(order, true);

        if (order.getStatus() != OrderStatus.PLACED) {
            throw new AppException("INVALID_STATE_TRANSITION",
                    "Order must be in PLACED status to confirm payment", HttpStatus.CONFLICT);
        }

        order.setStatus(OrderStatus.CONFIRMED_PAYMENT);
        order.setPaymentConfirmedAt(OffsetDateTime.now());
        orderRepository.save(order);

        // Accrue points for member
        memberRepository.findByUserId(order.getBuyerUserId()).ifPresent(member ->
            memberService.accruePoints(member.getId(), orderId, order.getTotalPayable())
        );

        auditService.record(order.getBuyerUserId(), "ORDER", orderId.toString(),
                "CONFIRM_PAYMENT", "PAYMENT_CONFIRMED", OrderStatus.PLACED, OrderStatus.CONFIRMED_PAYMENT);

        return toResponse(order);
    }

    @Transactional
    public OrderResponse fulfillOrder(UUID orderId) {
        permissionEnforcer.require("ORDER", "WRITE");
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new AppException("ORDER_NOT_FOUND", "Order not found", HttpStatus.NOT_FOUND));
        assertOrderOwnership(order, true);

        if (order.getStatus() != OrderStatus.CONFIRMED_PAYMENT) {
            throw new AppException("INVALID_STATE_TRANSITION",
                    "Order must be in CONFIRMED_PAYMENT status to fulfill", HttpStatus.CONFLICT);
        }

        // Q9: All-or-nothing fulfillment — verify that reserved inventory still covers
        // ALL items before transitioning. If the reservation window expired and the
        // timeout job already released stock for some SKUs, we must not partially fulfill.
        List<OrderItem> items = orderItemRepository.findByOrderId(orderId);
        Map<String, Integer> skuQuantities = new LinkedHashMap<>();
        for (OrderItem item : items) {
            skuQuantities.merge(item.getSku(), item.getQuantity(), Integer::sum);
        }

        List<String> insufficientSkus = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : skuQuantities.entrySet()) {
            Inventory inv = inventoryRepository.findBySkuWithLock(entry.getKey())
                    .orElse(null);
            if (inv == null || inv.getReserved() < entry.getValue()) {
                insufficientSkus.add(entry.getKey());
            }
        }
        if (!insufficientSkus.isEmpty()) {
            throw new AppException("FULFILLMENT_INVENTORY_INSUFFICIENT",
                    "Cannot fulfill: insufficient reserved inventory for SKU(s): " +
                    String.join(", ", insufficientSkus),
                    HttpStatus.UNPROCESSABLE_ENTITY);
        }

        order.setStatus(OrderStatus.FULFILLMENT_RESERVED);
        orderRepository.save(order);

        auditService.record(order.getBuyerUserId(), "ORDER", orderId.toString(),
                "FULFILL_ORDER", "USER_ACTION",
                Map.of("status", OrderStatus.CONFIRMED_PAYMENT.name()),
                Map.of("status", OrderStatus.FULFILLMENT_RESERVED.name()));

        return toResponse(order);
    }

    @Transactional
    public OrderResponse confirmDelivery(UUID orderId) {
        permissionEnforcer.require("ORDER", "WRITE");
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new AppException("ORDER_NOT_FOUND", "Order not found", HttpStatus.NOT_FOUND));
        assertOrderOwnership(order, true);

        if (order.getStatus() != OrderStatus.FULFILLMENT_RESERVED) {
            throw new AppException("INVALID_STATE_TRANSITION",
                    "Order must be in FULFILLMENT_RESERVED status", HttpStatus.CONFLICT);
        }
        order.setStatus(OrderStatus.DELIVERY_CONFIRMED);
        orderRepository.save(order);

        // Deduct from inventory reservation
        List<OrderItem> items = orderItemRepository.findByOrderId(orderId);
        for (OrderItem item : items) {
            inventoryRepository.findBySkuWithLock(item.getSku()).ifPresent(inv -> {
                inv.setReserved(Math.max(0, inv.getReserved() - item.getQuantity()));
                inv.setOnHand(Math.max(0, inv.getOnHand() - item.getQuantity()));
                inventoryRepository.save(inv);
            });
        }

        auditService.record(order.getBuyerUserId(), "ORDER", orderId.toString(),
                "CONFIRM_DELIVERY", "USER_ACTION",
                Map.of("status", OrderStatus.FULFILLMENT_RESERVED.name()),
                Map.of("status", OrderStatus.DELIVERY_CONFIRMED.name()));

        return toResponse(order);
    }

    @Transactional
    public OrderResponse completeOrder(UUID orderId) {
        permissionEnforcer.require("ORDER", "WRITE");
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new AppException("ORDER_NOT_FOUND", "Order not found", HttpStatus.NOT_FOUND));
        assertOrderOwnership(order, true);

        if (order.getStatus() != OrderStatus.DELIVERY_CONFIRMED) {
            throw new AppException("INVALID_STATE_TRANSITION",
                    "Order must be in DELIVERY_CONFIRMED status", HttpStatus.CONFLICT);
        }
        order.setStatus(OrderStatus.COMPLETED);
        orderRepository.save(order);

        auditService.record(order.getBuyerUserId(), "ORDER", orderId.toString(),
                "COMPLETE_ORDER", "USER_ACTION",
                Map.of("status", OrderStatus.DELIVERY_CONFIRMED.name()),
                Map.of("status", OrderStatus.COMPLETED.name()));

        return toResponse(order);
    }

    @Transactional
    public AttachmentResponse addAttachment(UUID orderId, String blobRef, String contentType, UUID actorId) {
        permissionEnforcer.require("ORDER", "WRITE");
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new AppException("ORDER_NOT_FOUND", "Order not found", HttpStatus.NOT_FOUND));
        assertOrderOwnership(order, true);

        Attachment attachment = Attachment.builder()
                .ownerType("ORDER")
                .ownerId(orderId)
                .blobRef(blobRef)
                .contentType(contentType)
                .build();
        attachment = attachmentRepository.save(attachment);

        auditService.record(actorId, "ORDER", orderId.toString(),
                "ADD_ATTACHMENT", "USER_ACTION",
                null, Map.of("blobRef", blobRef, "contentType", contentType != null ? contentType : ""));

        return AttachmentResponse.builder()
                .id(attachment.getId())
                .ownerType(attachment.getOwnerType())
                .ownerId(attachment.getOwnerId())
                .blobRef(attachment.getBlobRef())
                .contentType(attachment.getContentType())
                .createdAt(attachment.getCreatedAt())
                .build();
    }

    @Transactional
    public void addNote(UUID orderId, String note, UUID authorId) {
        permissionEnforcer.require("ORDER", "WRITE");
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new AppException("ORDER_NOT_FOUND", "Order not found", HttpStatus.NOT_FOUND));
        assertOrderOwnership(order, true);

        OrderNote orderNote = OrderNote.builder()
                .orderId(orderId)
                .authorUserId(authorId)
                .noteText(note)
                .build();
        orderNoteRepository.save(orderNote);

        auditService.record(authorId, "ORDER", orderId.toString(),
                "ADD_NOTE", "USER_ACTION",
                null, Map.of("note", note));
    }

    @Transactional
    public void closeTimedOutOrders() {
        OffsetDateTime cutoff = OffsetDateTime.now().minusMinutes(30);
        List<Order> timedOut = orderRepository.findTimedOutOrders(cutoff);

        for (Order order : timedOut) {
            log.info("Closing timed-out order: {}", order.getOrderNumber());
            // Release inventory
            List<OrderItem> items = orderItemRepository.findByOrderId(order.getId());
            for (OrderItem item : items) {
                inventoryRepository.findBySkuWithLock(item.getSku()).ifPresent(inv -> {
                    inv.setReserved(Math.max(0, inv.getReserved() - item.getQuantity()));
                    inventoryRepository.save(inv);
                });
            }
            order.setStatus(OrderStatus.CLOSED);
            order.setClosedAt(OffsetDateTime.now());
            orderRepository.save(order);
            auditService.record(null, "INVENTORY", order.getOrderNumber(),
                    "INVENTORY_RELEASE", "SYSTEM_TIMEOUT",
                    null, Map.of("orderId", order.getId().toString()));
        }
    }

    private BigDecimal computeShipping(String country) {
        // Simple flat rate
        if ("US".equalsIgnoreCase(country)) return BigDecimal.valueOf(10.00);
        if (country == null) return BigDecimal.valueOf(15.00);
        return BigDecimal.valueOf(25.00);
    }

    private String hashIdempotencyKey(UUID buyerId, String key) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest((buyerId.toString() + ":" + key).getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return key;
        }
    }

    public OrderResponse toResponse(Order order) {
        // Load items from DB (since items is @Transient on Order)
        List<OrderItem> dbItems = order.getItems() != null && !order.getItems().isEmpty()
                ? order.getItems()
                : orderItemRepository.findByOrderId(order.getId());
        List<OrderItemResponse> itemResponses = dbItems.stream()
                .map(this::toItemResponse).collect(Collectors.toList());

        return OrderResponse.builder()
                .id(order.getId())
                .orderNumber(order.getOrderNumber())
                .buyerUserId(order.getBuyerUserId())
                .status(order.getStatus())
                .subtotal(order.getSubtotal())
                .discountsTotal(order.getDiscountsTotal())
                .shippingTotal(order.getShippingTotal())
                .totalPayable(order.getTotalPayable())
                .paymentMethod(order.getPaymentMethod())
                .shippingCountry(order.getShippingCountry())
                .shippingPostalCode(order.getShippingPostalCode())
                .items(itemResponses)
                .createdAt(order.getCreatedAt())
                .closedAt(order.getClosedAt())
                .paymentConfirmedAt(order.getPaymentConfirmedAt())
                .build();
    }

    private OrderItemResponse toItemResponse(OrderItem item) {
        return OrderItemResponse.builder()
                .id(item.getId())
                .sku(item.getSku())
                .quantity(item.getQuantity())
                .unitPrice(item.getUnitPrice())
                .lineTotal(item.getLineTotal())
                .build();
    }
}
