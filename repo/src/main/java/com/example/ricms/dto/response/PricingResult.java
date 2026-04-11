package com.example.ricms.dto.response;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Internal + external representation of a computed pricing breakdown.
 *
 * Used by OrderService (internal) and by the coupon/campaign validate
 * endpoints (external preview).
 *
 * memberPricingEligible – true when the buyer's member status == ACTIVE (Q3).
 * totalPayable          – basis for points accrual (Q4): subtotal after
 *                         discounts + shipping after any waiver.
 */
@Data
@Builder
public class PricingResult {
    private boolean    memberPricingEligible;
    private BigDecimal finalSubtotal;
    private BigDecimal discountsTotal;
    private BigDecimal shippingTotal;
    private BigDecimal totalPayable;
    // Applied coupon
    private UUID       appliedCouponId;
    private String     appliedCouponCode;
    // Applied campaign
    private UUID       appliedCampaignId;
    private String     appliedCampaignName;
}
