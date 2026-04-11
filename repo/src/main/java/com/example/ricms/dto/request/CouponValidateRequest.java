package com.example.ricms.dto.request;

import lombok.Data;

@Data
public class CouponValidateRequest {
    private String couponCode;
    private OrderContextDto orderContext;
}
