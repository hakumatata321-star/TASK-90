package com.example.ricms.dto.response;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Response shape for POST /v1/coupons/validate (per api-spec.md §4.4).
 *
 * {
 *   "isValid": true,
 *   "couponCode": "SAVE50",
 *   "couponType": "THRESHOLD_DISCOUNT",
 *   "discount": { "amount": 50.00, "currency": "USD" },
 *   "waivedShipping": false,
 *   "reason": null
 * }
 */
@Data
@Builder
public class CouponValidateResponse {
    private boolean    isValid;
    private UUID       couponId;
    private String     couponCode;
    private String     couponType;
    /** Monetary discount off the item subtotal (0 for shipping waivers). */
    private BigDecimal discountAmount;
    private String     currency;
    /** True when the coupon waives shipping entirely instead of reducing subtotal. */
    private boolean    waivedShipping;
    /** Human-readable rejection reason when isValid == false. */
    private String     reason;
}
