package com.example.ricms.service;

import com.example.ricms.domain.entity.*;
import com.example.ricms.domain.enums.OrderStatus;
import com.example.ricms.domain.enums.PaymentMethod;
import com.example.ricms.dto.request.OrderItemDto;
import com.example.ricms.dto.request.PlaceOrderRequest;
import com.example.ricms.dto.response.OrderPlacementResult;
import com.example.ricms.dto.response.OrderResponse;
import com.example.ricms.dto.response.PageResponse;
import com.example.ricms.dto.response.PricingResult;
import com.example.ricms.exception.AppException;
import com.example.ricms.repository.*;
import com.example.ricms.security.PermissionEnforcer;
import com.example.ricms.security.RicmsPrincipal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.domain.PageImpl;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for OrderService covering:
 *  - idempotency key window enforcement (Q8)
 *  - all-or-nothing inventory reservation (Q9)
 *  - state machine transition guards
 *  - timeout-close job and inventory release (Q7)
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OrderServiceTest {

    @Mock OrderRepository       orderRepository;
    @Mock OrderItemRepository   orderItemRepository;
    @Mock OrderNoteRepository   orderNoteRepository;
    @Mock InventoryRepository   inventoryRepository;
    @Mock AttachmentRepository  attachmentRepository;
    @Mock MemberRepository      memberRepository;
    @Mock MemberService         memberService;
    @Mock PricingEngine         pricingEngine;
    @Mock AuditService          auditService;
    @Mock PermissionEnforcer    permissionEnforcer;

    OrderService service;

    private final UUID buyerId  = UUID.randomUUID();
    private final UUID orderId  = UUID.randomUUID();
    private final String IKEY   = "test-idempotency-key-001";

    @BeforeEach
    void setUp() {
        service = new OrderService(
                orderRepository, orderItemRepository, orderNoteRepository,
                inventoryRepository, attachmentRepository, memberRepository,
                memberService, pricingEngine, auditService, permissionEnforcer);

        setUpSecurityContext(buyerId, List.of());

        // Default: no members, no existing orders
        when(memberRepository.findByUserId(any())).thenReturn(Optional.empty());
        when(orderRepository.findTopByBuyerUserIdAndIdempotencyKeyHashOrderByCreatedAtDesc(any(), any()))
                .thenReturn(Optional.empty());

        // Default pricing result: no discounts
        when(pricingEngine.computePricing(any(), any(), any(), any(), any()))
                .thenReturn(PricingResult.builder()
                        .discountsTotal(BigDecimal.ZERO)
                        .shippingTotal(BigDecimal.TEN)
                        .totalPayable(new BigDecimal("209.99"))
                        .build());

        // Default save: echo the argument
        when(orderRepository.save(any())).thenAnswer(inv -> {
            Order o = inv.getArgument(0);
            if (o.getId() == null) {
                try {
                    var field = Order.class.getDeclaredField("id");
                    field.setAccessible(true);
                    field.set(o, UUID.randomUUID());
                } catch (Exception e) { /* ignore */ }
            }
            return o;
        });
        when(orderItemRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    // ── Idempotency (Q8) ─────────────────────────────────────────────────────

    @Test
    void placeOrder_sameKeyWithinWindow_returnsExistingOrder() {
        Order existing = existingOrder(OrderStatus.PLACED, OffsetDateTime.now().minusMinutes(5));
        String hash = sha256(buyerId + ":" + IKEY);
        when(orderRepository.findTopByBuyerUserIdAndIdempotencyKeyHashOrderByCreatedAtDesc(eq(buyerId), eq(hash)))
                .thenReturn(Optional.of(existing));
        when(orderItemRepository.findByOrderId(any())).thenReturn(List.of());

        OrderPlacementResult result = service.placeOrder(validRequest(), IKEY);

        assertThat(result.order().getId()).isEqualTo(existing.getId());
        assertThat(result.replay()).isTrue();
        // Inventory must NOT be touched again for the duplicate request
        verify(inventoryRepository, never()).findBySkuWithLock(any());
        verify(inventoryRepository, never()).save(any());
    }

    @Test
    void placeOrder_sameKeyAfterWindow_createsNewOrder() {
        // Existing order is 11 minutes old → outside the 10-minute window
        Order stale = existingOrder(OrderStatus.PLACED, OffsetDateTime.now().minusMinutes(11));
        String hash = sha256(buyerId + ":" + IKEY);
        when(orderRepository.findTopByBuyerUserIdAndIdempotencyKeyHashOrderByCreatedAtDesc(eq(buyerId), eq(hash)))
                .thenReturn(Optional.of(stale));

        // Inventory must be available for the new order
        stubInventory("SKU-001", 100, 0);

        OrderPlacementResult result = service.placeOrder(validRequest(), IKEY);

        // New order created (different ID from the stale one)
        assertThat(result.order().getId()).isNotEqualTo(stale.getId());
        assertThat(result.replay()).isFalse();
        verify(inventoryRepository, atLeastOnce()).save(any());
    }

    @Test
    void placeOrder_multipleHistoricalRowsSameHash_returnsLatest() {
        // Simulate the scenario where V6 dropped uniqueness and multiple rows
        // with the same hash exist. The repository now returns the most recent one.
        Order latestInWindow = existingOrder(OrderStatus.PLACED, OffsetDateTime.now().minusMinutes(3));
        String hash = sha256(buyerId + ":" + IKEY);
        when(orderRepository.findTopByBuyerUserIdAndIdempotencyKeyHashOrderByCreatedAtDesc(eq(buyerId), eq(hash)))
                .thenReturn(Optional.of(latestInWindow));
        when(orderItemRepository.findByOrderId(any())).thenReturn(List.of());

        OrderPlacementResult result = service.placeOrder(validRequest(), IKEY);

        // Should return the latest order as replay (within 10-min window)
        assertThat(result.replay()).isTrue();
        assertThat(result.order().getId()).isEqualTo(latestInWindow.getId());
        verify(inventoryRepository, never()).findBySkuWithLock(any());
    }

    // ── Inventory reservation all-or-nothing (Q9) ────────────────────────────

    @Test
    void placeOrder_insufficientInventory_throws422() {
        stubInventory("SKU-001", 1, 1); // on_hand=1, reserved=1 → available=0

        assertThatThrownBy(() -> service.placeOrder(validRequest(), IKEY))
                .isInstanceOf(AppException.class)
                .satisfies(e -> {
                    AppException ae = (AppException) e;
                    assertThat(ae.getCode()).isEqualTo("INSUFFICIENT_INVENTORY");
                    assertThat(ae.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                });

        // No inventory row must have been saved (all-or-nothing)
        verify(inventoryRepository, never()).save(any());
    }

    @Test
    void placeOrder_skuNotFound_throws404() {
        when(inventoryRepository.findBySkuWithLock("SKU-001")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.placeOrder(validRequest(), IKEY))
                .isInstanceOf(AppException.class)
                .satisfies(e -> assertThat(((AppException) e).getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    void placeOrder_availableInventory_reservesCorrectly() {
        stubInventory("SKU-001", 100, 5); // available = 95

        OrderPlacementResult result = service.placeOrder(validRequest(), IKEY);

        assertThat(result.replay()).isFalse();
        // Reserved count must increase by the requested quantity (2)
        verify(inventoryRepository).save(argThat(inv ->
                ((Inventory) inv).getReserved() == 7 // 5 + 2
        ));
    }

    // ── State machine transitions ─────────────────────────────────────────────

    @Test
    void confirmPayment_fromPlaced_succeeds() {
        Order order = existingOrder(OrderStatus.PLACED, OffsetDateTime.now().minusMinutes(1));
        when(orderRepository.findById(orderId)).thenReturn(Optional.of(order));

        OrderResponse r = service.confirmPayment(orderId, "PAY-REF-123");

        assertThat(r.getStatus()).isEqualTo(OrderStatus.CONFIRMED_PAYMENT);
        verify(orderRepository).save(argThat(o -> ((Order) o).getStatus() == OrderStatus.CONFIRMED_PAYMENT));
    }

    @Test
    void confirmPayment_fromNonPlaced_throws409() {
        Order order = existingOrder(OrderStatus.CONFIRMED_PAYMENT, OffsetDateTime.now().minusMinutes(1));
        when(orderRepository.findById(orderId)).thenReturn(Optional.of(order));

        assertThatThrownBy(() -> service.confirmPayment(orderId, "REF"))
                .isInstanceOf(AppException.class)
                .satisfies(e -> {
                    AppException ae = (AppException) e;
                    assertThat(ae.getCode()).isEqualTo("INVALID_STATE_TRANSITION");
                    assertThat(ae.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                });
    }

    @Test
    void fulfillOrder_fromConfirmedPayment_withAllInventoryReserved_succeeds() {
        Order order = existingOrder(OrderStatus.CONFIRMED_PAYMENT, OffsetDateTime.now().minusMinutes(1));
        when(orderRepository.findById(orderId)).thenReturn(Optional.of(order));
        when(orderItemRepository.findByOrderId(orderId)).thenReturn(
                List.of(item("SKU-001", 2)));
        stubInventory("SKU-001", 100, 10); // reserved=10 >= quantity=2

        OrderResponse r = service.fulfillOrder(orderId);

        assertThat(r.getStatus()).isEqualTo(OrderStatus.FULFILLMENT_RESERVED);
    }

    @Test
    void fulfillOrder_fromPlaced_throws409() {
        Order order = existingOrder(OrderStatus.PLACED, OffsetDateTime.now().minusMinutes(1));
        when(orderRepository.findById(orderId)).thenReturn(Optional.of(order));

        assertThatThrownBy(() -> service.fulfillOrder(orderId))
                .isInstanceOf(AppException.class)
                .satisfies(e -> assertThat(((AppException) e).getStatus()).isEqualTo(HttpStatus.CONFLICT));
    }

    @Test
    void fulfillOrder_insufficientReservedInventory_throws422() {
        Order order = existingOrder(OrderStatus.CONFIRMED_PAYMENT, OffsetDateTime.now().minusMinutes(1));
        when(orderRepository.findById(orderId)).thenReturn(Optional.of(order));
        when(orderItemRepository.findByOrderId(orderId)).thenReturn(List.of(item("SKU-001", 5)));
        stubInventory("SKU-001", 100, 3); // reserved=3 < quantity=5 → insufficient

        assertThatThrownBy(() -> service.fulfillOrder(orderId))
                .isInstanceOf(AppException.class)
                .satisfies(e -> {
                    AppException ae = (AppException) e;
                    assertThat(ae.getCode()).isEqualTo("FULFILLMENT_INVENTORY_INSUFFICIENT");
                    assertThat(ae.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                });
    }

    @Test
    void completeOrder_fromDeliveryConfirmed_succeeds() {
        Order order = existingOrder(OrderStatus.DELIVERY_CONFIRMED, OffsetDateTime.now().minusMinutes(1));
        when(orderRepository.findById(orderId)).thenReturn(Optional.of(order));

        OrderResponse r = service.completeOrder(orderId);

        assertThat(r.getStatus()).isEqualTo(OrderStatus.COMPLETED);
    }

    @Test
    void completeOrder_fromPlaced_throws409() {
        Order order = existingOrder(OrderStatus.PLACED, OffsetDateTime.now().minusMinutes(1));
        when(orderRepository.findById(orderId)).thenReturn(Optional.of(order));

        assertThatThrownBy(() -> service.completeOrder(orderId))
                .isInstanceOf(AppException.class)
                .satisfies(e -> assertThat(((AppException) e).getStatus()).isEqualTo(HttpStatus.CONFLICT));
    }

    // ── Timeout close + inventory release (Q7) ───────────────────────────────

    @Test
    void closeTimedOutOrders_releasesInventoryAndClosesOrder() {
        Order order = existingOrder(OrderStatus.PLACED, OffsetDateTime.now().minusMinutes(35));
        when(orderRepository.findTimedOutOrders(any())).thenReturn(List.of(order));
        when(orderItemRepository.findByOrderId(order.getId())).thenReturn(List.of(item("SKU-001", 2)));

        Inventory inv = inventory("SKU-001", 100, 10);
        when(inventoryRepository.findBySkuWithLock("SKU-001")).thenReturn(Optional.of(inv));

        service.closeTimedOutOrders();

        // Reserved count decremented by 2 (idempotent max-zero guard applied)
        assertThat(inv.getReserved()).isEqualTo(8); // 10 - 2
        assertThat(order.getStatus()).isEqualTo(OrderStatus.CLOSED);
        assertThat(order.getClosedAt()).isNotNull();
        verify(orderRepository, atLeastOnce()).save(order);
    }

    @Test
    void closeTimedOutOrders_idempotent_reservedNeverGoesNegative() {
        Order order = existingOrder(OrderStatus.PLACED, OffsetDateTime.now().minusMinutes(35));
        when(orderRepository.findTimedOutOrders(any())).thenReturn(List.of(order));
        when(orderItemRepository.findByOrderId(order.getId())).thenReturn(List.of(item("SKU-001", 10)));

        // Reserved is already 0 (previously released)
        Inventory inv = inventory("SKU-001", 100, 0);
        when(inventoryRepository.findBySkuWithLock("SKU-001")).thenReturn(Optional.of(inv));

        service.closeTimedOutOrders();

        assertThat(inv.getReserved()).isEqualTo(0); // max(0, 0-10) = 0
    }

    // ── Row-Level Security (Fix 2) ───────────────────────────────────────────

    @Test
    void getOrders_asMember_onlyReturnsMemberOrders() {
        UUID buyerA = UUID.randomUUID();
        UUID buyerB = UUID.randomUUID();

        // Buyer A is authenticated with no admin permission
        setUpSecurityContext(buyerA, List.of());

        // Build one order for buyer A (the authenticated user)
        Order orderA = existingOrder(OrderStatus.PLACED, OffsetDateTime.now().minusMinutes(1));
        orderA.setBuyerUserId(buyerA);

        // Repository returns buyer A's page when queried with buyerA's ID
        when(orderRepository.findByBuyerUserIdWithFilters(eq(buyerA), isNull(), isNull(), any()))
                .thenReturn(new PageImpl<>(List.of(orderA)));
        // Buyer B's orders should never be visible to buyer A
        when(orderRepository.findByBuyerUserIdWithFilters(eq(buyerB), any(), any(), any()))
                .thenReturn(new PageImpl<>(List.of()));
        when(orderItemRepository.findByOrderId(any())).thenReturn(List.of());

        // Call with no buyerId param (would default to null → admin sees all)
        PageResponse<OrderResponse> result = service.getOrders(0, 20, null, null, null);

        // Non-admin: only buyer A's own orders returned regardless of param
        assertThat(result.getContent()).hasSize(1);
        assertThat(result.getContent().get(0).getBuyerUserId()).isEqualTo(buyerA);
        // Verify the repo was called with buyerA's ID, not null
        verify(orderRepository).findByBuyerUserIdWithFilters(eq(buyerA), isNull(), isNull(), any());
        verify(orderRepository, never()).findByBuyerUserIdWithFilters(eq(buyerB), any(), any(), any());
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    private void setUpSecurityContext(UUID userId, Collection<? extends GrantedAuthority> authorities) {
        RicmsPrincipal principal = new RicmsPrincipal(userId, "user-" + userId, "", authorities);
        var auth = new UsernamePasswordAuthenticationToken(principal, null, authorities);
        var ctx = SecurityContextHolder.createEmptyContext();
        ctx.setAuthentication(auth);
        SecurityContextHolder.setContext(ctx);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private PlaceOrderRequest validRequest() {
        OrderItemDto item = new OrderItemDto();
        item.setSku("SKU-001");
        item.setQuantity(2);
        item.setUnitPrice(new BigDecimal("99.99"));

        PlaceOrderRequest req = new PlaceOrderRequest();
        req.setBuyerId(buyerId);
        req.setItems(List.of(item));
        req.setShippingCountry("US");
        req.setPaymentMethod(PaymentMethod.CARD_ON_FILE);
        return req;
    }

    private Order existingOrder(OrderStatus status, OffsetDateTime createdAt) {
        Order o = new Order();
        try {
            var idField = Order.class.getDeclaredField("id");
            idField.setAccessible(true);
            idField.set(o, orderId);
            var caField = Order.class.getDeclaredField("createdAt");
            caField.setAccessible(true);
            caField.set(o, createdAt);
        } catch (Exception e) { /* ignore */ }
        o.setStatus(status);
        o.setBuyerUserId(buyerId);
        o.setOrderNumber("ORD-TEST-001");
        o.setSubtotal(new BigDecimal("199.98"));
        o.setDiscountsTotal(BigDecimal.ZERO);
        o.setShippingTotal(BigDecimal.TEN);
        o.setTotalPayable(new BigDecimal("209.98"));
        o.setPaymentMethod(PaymentMethod.CARD_ON_FILE);
        return o;
    }

    private void stubInventory(String sku, int onHand, int reserved) {
        when(inventoryRepository.findBySkuWithLock(sku))
                .thenReturn(Optional.of(inventory(sku, onHand, reserved)));
    }

    private Inventory inventory(String sku, int onHand, int reserved) {
        Inventory inv = new Inventory();
        inv.setSku(sku);
        inv.setOnHand(onHand);
        inv.setReserved(reserved);
        return inv;
    }

    private OrderItem item(String sku, int qty) {
        OrderItem i = new OrderItem();
        i.setSku(sku);
        i.setQuantity(qty);
        i.setOrderId(orderId);
        i.setUnitPrice(new BigDecimal("50.00"));
        i.setLineTotal(new BigDecimal("100.00"));
        return i;
    }

    /** Minimal SHA-256 hex replication of the service logic for hash matching. */
    private String sha256(String input) {
        try {
            var md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(input.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            var sb = new StringBuilder();
            for (byte b : hash) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) { return input; }
    }
}
