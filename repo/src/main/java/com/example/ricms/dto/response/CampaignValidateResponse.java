package com.example.ricms.dto.response;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Response shape for POST /v1/campaigns/validate (per api-spec.md §4.5).
 *
 * {
 *   "isValid": true,
 *   "campaignId": "...",
 *   "campaignName": "Spend 1000 Get 200 Off",
 *   "campaignType": "SPEND_AND_GET",
 *   "discount": { "amount": 200.00, "currency": "USD" },
 *   "reason": null
 * }
 */
@Data
@Builder
public class CampaignValidateResponse {
    private boolean    isValid;
    private UUID       campaignId;
    private String     campaignName;
    private String     campaignType;
    private BigDecimal discountAmount;
    private String     currency;
    /** Human-readable reason when isValid == false. */
    private String     reason;
}
