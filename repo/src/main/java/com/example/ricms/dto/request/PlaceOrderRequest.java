package com.example.ricms.dto.request;

import com.example.ricms.domain.enums.PaymentMethod;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;
import java.util.UUID;

@Data
public class PlaceOrderRequest {

    @NotNull
    private UUID buyerId;

    @NotEmpty
    @Valid
    private List<OrderItemDto> items;

    private String shippingCountry;
    private String shippingPostalCode;
    private String couponCode;
    private UUID campaignId;

    @NotNull
    private PaymentMethod paymentMethod;
}
