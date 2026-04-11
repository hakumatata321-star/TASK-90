package com.example.ricms.controller;

import com.example.ricms.domain.entity.Campaign;
import com.example.ricms.dto.request.CampaignValidateRequest;
import com.example.ricms.dto.response.CampaignValidateResponse;
import com.example.ricms.repository.CampaignRepository;
import com.example.ricms.service.PricingEngine;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

/**
 * POST /v1/campaigns/validate
 *
 * Returns the discount a specific campaign would produce for the supplied
 * order context. Does not mutate state.
 */
@Slf4j
@RestController
@RequestMapping("/v1/campaigns")
@RequiredArgsConstructor
public class CampaignController {

    private final CampaignRepository campaignRepository;
    private final PricingEngine pricingEngine;
    private final ObjectMapper objectMapper;

    @PostMapping("/validate")
    public ResponseEntity<CampaignValidateResponse> validate(
            @RequestBody CampaignValidateRequest request) {

        UUID campaignId = request.getCampaignId();
        BigDecimal sub  = contextSubtotal(request);
        OffsetDateTime now = OffsetDateTime.now();

        if (campaignId == null) {
            return ResponseEntity.ok(reject(null, "Campaign ID is required"));
        }

        Optional<Campaign> opt = campaignRepository.findById(campaignId);
        if (opt.isEmpty()) {
            return ResponseEntity.ok(reject(campaignId, "Campaign not found"));
        }

        Campaign c = opt.get();
        if (!c.isActive()) {
            return ResponseEntity.ok(reject(campaignId, "Campaign is inactive"));
        }
        if (c.getActiveFrom() != null && c.getActiveFrom().isAfter(now)) {
            return ResponseEntity.ok(reject(campaignId, "Campaign has not started yet"));
        }
        if (c.getActiveTo() != null && c.getActiveTo().isBefore(now)) {
            return ResponseEntity.ok(reject(campaignId, "Campaign has expired"));
        }

        BigDecimal discount = computeDiscount(c, sub);
        if (discount.compareTo(BigDecimal.ZERO) <= 0) {
            return ResponseEntity.ok(reject(campaignId,
                    "Order does not meet the campaign's eligibility conditions"));
        }

        return ResponseEntity.ok(CampaignValidateResponse.builder()
                .isValid(true)
                .campaignId(c.getId())
                .campaignName(c.getName())
                .campaignType(c.getType().name())
                .discountAmount(discount)
                .currency("USD")
                .build());
    }

    // ------------------------------------------------------------------

    private BigDecimal computeDiscount(Campaign c, BigDecimal subtotal) {
        try {
            if (c.getParams() == null) return BigDecimal.ZERO;
            JsonNode params = objectMapper.readTree(c.getParams());

            return switch (c.getType()) {
                case SPEND_AND_GET -> {
                    BigDecimal threshold = decimal(params, "spendThreshold");
                    yield subtotal.compareTo(threshold) >= 0
                            ? decimal(params, "rewardAmount")
                            : BigDecimal.ZERO;
                }
                case SECOND_ITEM_DISCOUNT -> {
                    BigDecimal pct     = decimal(params, "discountPercent");
                    BigDecimal halfSub = subtotal.divide(BigDecimal.valueOf(2), 2, RoundingMode.HALF_UP);
                    yield halfSub.multiply(pct).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
                }
            };
        } catch (Exception e) {
            log.warn("Campaign discount computation failed: {}", e.getMessage());
            return BigDecimal.ZERO;
        }
    }

    private BigDecimal decimal(JsonNode node, String field) {
        JsonNode n = node.get(field);
        return (n != null && !n.isNull())
                ? BigDecimal.valueOf(n.asDouble()).setScale(2, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;
    }

    private BigDecimal contextSubtotal(CampaignValidateRequest req) {
        if (req.getOrderContext() != null && req.getOrderContext().getSubtotal() != null)
            return req.getOrderContext().getSubtotal();
        return BigDecimal.ZERO;
    }

    private CampaignValidateResponse reject(UUID campaignId, String reason) {
        return CampaignValidateResponse.builder()
                .isValid(false)
                .campaignId(campaignId)
                .discountAmount(BigDecimal.ZERO)
                .currency("USD")
                .reason(reason)
                .build();
    }
}
