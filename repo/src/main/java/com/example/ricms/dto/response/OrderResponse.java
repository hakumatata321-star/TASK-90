package com.example.ricms.dto.response;

import com.example.ricms.domain.enums.OrderStatus;
import com.example.ricms.domain.enums.PaymentMethod;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Data
@Builder
public class OrderResponse {
    private UUID id;
    private String orderNumber;
    private UUID buyerUserId;
    private OrderStatus status;
    private BigDecimal subtotal;
    private BigDecimal discountsTotal;
    private BigDecimal shippingTotal;
    private BigDecimal totalPayable;
    private PaymentMethod paymentMethod;
    private String shippingCountry;
    private String shippingPostalCode;
    private List<OrderItemResponse> items;
    private OffsetDateTime createdAt;
    private OffsetDateTime closedAt;
    private OffsetDateTime paymentConfirmedAt;
}
