package com.example.ricms.dto.request;

import lombok.Data;

import java.math.BigDecimal;
import java.util.UUID;

@Data
public class OrderContextDto {
    private BigDecimal subtotal;
    private BigDecimal shippingCost;
    private UUID buyerId;
}
