package com.example.ricms.service;

import com.example.ricms.domain.entity.Campaign;
import com.example.ricms.domain.entity.Coupon;
import com.example.ricms.domain.entity.Member;
import com.example.ricms.domain.enums.CampaignType;
import com.example.ricms.domain.enums.CouponType;
import com.example.ricms.domain.enums.MemberStatus;
import com.example.ricms.dto.response.PricingResult;
import com.example.ricms.repository.CampaignRepository;
import com.example.ricms.repository.CouponRepository;
import com.example.ricms.repository.MemberRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Deterministic pricing engine (Q3 / Q4 / Q6).
 *
 * Priority chain (Q6):
 *   1. Member price  – only if member status == ACTIVE (Q3)
 *   2. Fixed-amount (threshold) coupon
 *   3. Percentage coupon  (capped at maxDiscountAmount)
 *   4. Shipping-waiver coupon
 *
 * Stacking (Q6):
 *   – At most ONE coupon (client-supplied code).
 *   – At most ONE campaign (client-supplied ID, or auto-selected highest-priority eligible).
 *   – Coupon discount and campaign discount are cumulative; they are not in competition.
 *     The priority chain only governs which coupon TYPE is used when the client provides
 *     a code whose type conflicts with another eligible type – in practice the client
 *     names exactly one code, so the chain resolves ambiguity in auto-selection flows.
 *
 * Points basis (Q4):
 *   totalPayable returned here is the value used by OrderService to accrue points.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PricingEngine {

    private final CouponRepository couponRepository;
    private final CampaignRepository campaignRepository;
    private final MemberRepository memberRepository;
    private final ObjectMapper objectMapper;

    // ------------------------------------------------------------------
    // Public API
    // ------------------------------------------------------------------

    /**
     * Compute the full pricing breakdown for an order.
     *
     * @param buyerId      the purchasing user's ID (may be null for anonymous)
     * @param subtotal     pre-discount item subtotal
     * @param shippingCost raw shipping cost before any waiver
     * @param couponCode   optional coupon code supplied by the buyer
     * @param campaignId   optional campaign ID supplied by the buyer;
     *                     if null the engine auto-selects the best eligible campaign
     */
    public PricingResult computePricing(UUID buyerId,
                                        BigDecimal subtotal,
                                        BigDecimal shippingCost,
                                        String couponCode,
                                        UUID campaignId) {

        BigDecimal sub        = nullSafe(subtotal);
        BigDecimal ship       = nullSafe(shippingCost);
        OffsetDateTime now    = OffsetDateTime.now();

        // ── Step 1: Member status gate (Q3) ──────────────────────────────
        boolean memberActive = isMemberActive(buyerId);

        // ── Step 2: Resolve coupon ────────────────────────────────────────
        CouponApplication coupon = resolveCoupon(couponCode, sub, ship, memberActive, now);

        // ── Step 3: Resolve campaign ──────────────────────────────────────
        //   If the buyer specified a campaign ID, use that.
        //   Otherwise auto-select the highest-priority eligible campaign (Q6).
        CampaignApplication campaign = resolveCampaign(campaignId, sub, memberActive, now);

        // ── Step 4: Compute totals ────────────────────────────────────────
        BigDecimal couponDiscount   = coupon.discount;
        BigDecimal campaignDiscount = campaign.discount;
        BigDecimal totalDiscount    = couponDiscount.add(campaignDiscount);

        // Discounts must not exceed the item subtotal
        if (totalDiscount.compareTo(sub) > 0) {
            totalDiscount = sub;
        }

        BigDecimal finalSubtotal = sub.subtract(totalDiscount).max(BigDecimal.ZERO);
        BigDecimal finalShipping = coupon.waivedShipping ? BigDecimal.ZERO : ship;
        BigDecimal totalPayable  = finalSubtotal.add(finalShipping);

        return PricingResult.builder()
                .memberPricingEligible(memberActive)
                .finalSubtotal(finalSubtotal)
                .discountsTotal(totalDiscount)
                .shippingTotal(finalShipping)
                .totalPayable(totalPayable)
                .appliedCouponId(coupon.id)
                .appliedCouponCode(coupon.code)
                .appliedCampaignId(campaign.id)
                .appliedCampaignName(campaign.name)
                .build();
    }

    // ------------------------------------------------------------------
    // Member status check (Q3)
    // ------------------------------------------------------------------

    private boolean isMemberActive(UUID buyerId) {
        if (buyerId == null) return false;
        return memberRepository.findByUserId(buyerId)
                .map(m -> m.getStatus() == MemberStatus.ACTIVE)
                .orElse(false);
    }

    // ------------------------------------------------------------------
    // Coupon resolution  (Q6 priority chain)
    // ------------------------------------------------------------------

    /**
     * Validate and apply a single, explicitly provided coupon code.
     *
     * If the coupon exists and is active, compute its discount; otherwise
     * the result has zero discount and a reason explaining why it was rejected.
     *
     * Priority chain (governs auto-selection if ever extended; here the client
     * names one code so we validate and apply it directly):
     *   THRESHOLD_DISCOUNT > PERCENTAGE_DISCOUNT > SHIPPING_WAIVER
     *
     * Member pricing gate: if the coupon is tagged member-only (future flag),
     * memberActive must be true. For now all coupons are universally available;
     * the memberActive flag is passed through for forward-compatibility.
     */
    private CouponApplication resolveCoupon(String couponCode,
                                             BigDecimal subtotal,
                                             BigDecimal shippingCost,
                                             boolean memberActive,
                                             OffsetDateTime now) {
        if (couponCode == null || couponCode.isBlank()) {
            return CouponApplication.none();
        }

        Optional<Coupon> opt = couponRepository.findByCode(couponCode.toUpperCase().trim());
        if (opt.isEmpty()) {
            return CouponApplication.rejected("Coupon not found");
        }

        Coupon c = opt.get();
        if (!c.isActive()) {
            return CouponApplication.rejected("Coupon is inactive");
        }
        if (c.getActiveFrom() != null && c.getActiveFrom().isAfter(now)) {
            return CouponApplication.rejected("Coupon has not started yet");
        }
        if (c.getActiveTo() != null && c.getActiveTo().isBefore(now)) {
            return CouponApplication.rejected("Coupon has expired");
        }

        return switch (c.getType()) {
            case THRESHOLD_DISCOUNT -> applyThreshold(c, subtotal);
            case PERCENTAGE_DISCOUNT -> applyPercentage(c, subtotal);
            case SHIPPING_WAIVER -> applyShippingWaiver(c, subtotal);
        };
    }

    /** $X off orders ≥ threshold. */
    private CouponApplication applyThreshold(Coupon c, BigDecimal subtotal) {
        BigDecimal threshold = nullSafe(c.getThresholdAmount());
        if (subtotal.compareTo(threshold) < 0) {
            return CouponApplication.rejected(
                    "Order subtotal $" + subtotal.toPlainString() +
                    " below required threshold $" + threshold.toPlainString());
        }
        BigDecimal discount = nullSafe(c.getDiscountAmount());
        return CouponApplication.of(c.getId(), c.getCode(), discount, false);
    }

    /**
     * X% off, capped at the coupon's maxDiscountAmount AND the system-wide
     * percentage discount ceiling of $25 (prompt requirement).
     */
    private static final BigDecimal SYSTEM_PCT_DISCOUNT_CAP = new BigDecimal("25.00");

    private CouponApplication applyPercentage(Coupon c, BigDecimal subtotal) {
        BigDecimal pct      = nullSafe(c.getDiscountPercent());
        BigDecimal computed = subtotal.multiply(pct)
                .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
        // Per-coupon cap
        if (c.getMaxDiscountAmount() != null && computed.compareTo(c.getMaxDiscountAmount()) > 0) {
            computed = c.getMaxDiscountAmount();
        }
        // System-wide percentage discount ceiling ($25 per prompt)
        if (computed.compareTo(SYSTEM_PCT_DISCOUNT_CAP) > 0) {
            computed = SYSTEM_PCT_DISCOUNT_CAP;
        }
        return CouponApplication.of(c.getId(), c.getCode(), computed, false);
    }

    /** Zero shipping if subtotal ≥ threshold. */
    private CouponApplication applyShippingWaiver(Coupon c, BigDecimal subtotal) {
        BigDecimal threshold = nullSafe(c.getThresholdAmount());
        if (threshold.compareTo(BigDecimal.ZERO) > 0 && subtotal.compareTo(threshold) < 0) {
            return CouponApplication.rejected(
                    "Order subtotal below shipping waiver threshold $" + threshold.toPlainString());
        }
        return CouponApplication.of(c.getId(), c.getCode(), BigDecimal.ZERO, true);
    }

    // ------------------------------------------------------------------
    // Campaign resolution (Q6)
    // ------------------------------------------------------------------

    /**
     * Select and compute the campaign discount.
     *
     * If campaignId is provided: validate and apply that campaign.
     * If null: scan all currently active campaigns, compute each eligible
     * discount, and pick the one with the highest {@code priority} value (Q6).
     */
    private CampaignApplication resolveCampaign(UUID campaignId,
                                                  BigDecimal subtotal,
                                                  boolean memberActive,
                                                  OffsetDateTime now) {
        if (campaignId != null) {
            return campaignRepository.findById(campaignId)
                    .map(c -> evaluateCampaign(c, subtotal, memberActive, now))
                    .orElse(CampaignApplication.none());
        }

        // Auto-select: highest-priority eligible campaign (Q6)
        List<Campaign> active = campaignRepository.findActiveCampaignsByNow(now);
        // list is already ordered by priority DESC from the repository query
        for (Campaign c : active) {
            CampaignApplication result = evaluateCampaign(c, subtotal, memberActive, now);
            if (result.id != null) {
                return result;   // first eligible is highest priority
            }
        }
        return CampaignApplication.none();
    }

    private CampaignApplication evaluateCampaign(Campaign c, BigDecimal subtotal,
                                                   boolean memberActive,
                                                   OffsetDateTime now) {
        if (!c.isActive()) return CampaignApplication.none();
        if (c.getActiveFrom() != null && c.getActiveFrom().isAfter(now)) return CampaignApplication.none();
        if (c.getActiveTo()   != null && c.getActiveTo().isBefore(now))  return CampaignApplication.none();

        BigDecimal discount = computeCampaignDiscount(c, subtotal);
        if (discount.compareTo(BigDecimal.ZERO) <= 0) return CampaignApplication.none();
        return CampaignApplication.of(c.getId(), c.getName(), discount);
    }

    /**
     * Compute the monetary discount amount for a campaign given the order subtotal.
     *
     * SPEND_AND_GET: flat reward if subtotal ≥ spendThreshold.
     * SECOND_ITEM_DISCOUNT: discountPercent% off half the subtotal
     *   (approximation: assumes items of equal value; real implementation
     *    needs item-level data passed from OrderService).
     */
    private BigDecimal computeCampaignDiscount(Campaign campaign, BigDecimal subtotal) {
        try {
            String paramsJson = campaign.getParams();
            if (paramsJson == null || paramsJson.isBlank()) return BigDecimal.ZERO;
            JsonNode params = objectMapper.readTree(paramsJson);

            return switch (campaign.getType()) {
                case SPEND_AND_GET -> {
                    BigDecimal threshold = decimal(params, "spendThreshold");
                    if (subtotal.compareTo(threshold) >= 0) {
                        yield decimal(params, "rewardAmount");
                    }
                    yield BigDecimal.ZERO;
                }
                case SECOND_ITEM_DISCOUNT -> {
                    // Apply discountPercent% off half the subtotal
                    // (represents one second item at X% off when all items are equal price).
                    BigDecimal pct        = decimal(params, "discountPercent");
                    BigDecimal halfSub    = subtotal.divide(BigDecimal.valueOf(2), 2, RoundingMode.HALF_UP);
                    yield halfSub.multiply(pct).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
                }
            };
        } catch (Exception e) {
            log.warn("Could not compute campaign discount for campaign {}: {}", campaign.getId(), e.getMessage());
            return BigDecimal.ZERO;
        }
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private BigDecimal decimal(JsonNode node, String field) {
        JsonNode n = node.get(field);
        if (n == null || n.isNull()) return BigDecimal.ZERO;
        return BigDecimal.valueOf(n.asDouble()).setScale(2, RoundingMode.HALF_UP);
    }

    private static BigDecimal nullSafe(BigDecimal v) {
        return v != null ? v : BigDecimal.ZERO;
    }

    // ------------------------------------------------------------------
    // Value objects (package-private for testability)
    // ------------------------------------------------------------------

    static class CouponApplication {
        final UUID       id;
        final String     code;
        final BigDecimal discount;
        final boolean    waivedShipping;
        final String     rejectionReason;

        private CouponApplication(UUID id, String code, BigDecimal discount,
                                   boolean waivedShipping, String rejectionReason) {
            this.id              = id;
            this.code            = code;
            this.discount        = discount;
            this.waivedShipping  = waivedShipping;
            this.rejectionReason = rejectionReason;
        }

        static CouponApplication none() {
            return new CouponApplication(null, null, BigDecimal.ZERO, false, null);
        }
        static CouponApplication rejected(String reason) {
            return new CouponApplication(null, null, BigDecimal.ZERO, false, reason);
        }
        static CouponApplication of(UUID id, String code, BigDecimal discount, boolean waiveShipping) {
            return new CouponApplication(id, code, discount, waiveShipping, null);
        }
        boolean isValid() { return id != null; }
    }

    static class CampaignApplication {
        final UUID       id;
        final String     name;
        final BigDecimal discount;

        private CampaignApplication(UUID id, String name, BigDecimal discount) {
            this.id       = id;
            this.name     = name;
            this.discount = discount;
        }

        static CampaignApplication none() {
            return new CampaignApplication(null, null, BigDecimal.ZERO);
        }
        static CampaignApplication of(UUID id, String name, BigDecimal discount) {
            return new CampaignApplication(id, name, discount);
        }
    }
}
