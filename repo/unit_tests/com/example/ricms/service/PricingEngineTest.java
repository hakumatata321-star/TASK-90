package com.example.ricms.service;

import com.example.ricms.domain.entity.Campaign;
import com.example.ricms.domain.entity.Coupon;
import com.example.ricms.domain.entity.Member;
import com.example.ricms.domain.enums.CampaignType;
import com.example.ricms.domain.enums.CouponType;
import com.example.ricms.domain.enums.MemberStatus;
import com.example.ricms.domain.enums.MemberTier;
import com.example.ricms.dto.response.PricingResult;
import com.example.ricms.repository.CampaignRepository;
import com.example.ricms.repository.CouponRepository;
import com.example.ricms.repository.MemberRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for PricingEngine covering:
 *  - member status gate (Q3)
 *  - coupon priority chain (Q6)
 *  - campaign discount calculation
 *  - discount cap at subtotal
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PricingEngineTest {

    @Mock CouponRepository couponRepository;
    @Mock CampaignRepository campaignRepository;
    @Mock MemberRepository memberRepository;

    PricingEngine engine;

    private final UUID buyerId    = UUID.randomUUID();
    private final UUID couponId   = UUID.randomUUID();
    private final UUID campaignId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        engine = new PricingEngine(couponRepository, campaignRepository, memberRepository, new ObjectMapper());
        // Default: no active campaigns (avoids unexpected auto-selection)
        when(campaignRepository.findActiveCampaignsByNow(any())).thenReturn(List.of());
        // Default: no coupon found
        when(couponRepository.findByCode(any())).thenReturn(Optional.empty());
        // Default: no member
        when(memberRepository.findByUserId(any())).thenReturn(Optional.empty());
    }

    // ── Member status gate (Q3) ──────────────────────────────────────────────

    @Test
    void activeMember_enablesMemberPricing() {
        Member m = member(MemberStatus.ACTIVE);
        when(memberRepository.findByUserId(buyerId)).thenReturn(Optional.of(m));

        PricingResult r = engine.computePricing(buyerId, bd("100"), bd("10"), null, null);

        assertThat(r.isMemberPricingEligible()).isTrue();
    }

    @Test
    void suspendedMember_disablesMemberPricing() {
        Member m = member(MemberStatus.SUSPENDED);
        when(memberRepository.findByUserId(buyerId)).thenReturn(Optional.of(m));

        PricingResult r = engine.computePricing(buyerId, bd("100"), bd("10"), null, null);

        assertThat(r.isMemberPricingEligible()).isFalse();
    }

    @Test
    void nullBuyerId_disablesMemberPricing() {
        PricingResult r = engine.computePricing(null, bd("100"), bd("10"), null, null);

        assertThat(r.isMemberPricingEligible()).isFalse();
    }

    // ── THRESHOLD_DISCOUNT coupon (Q6) ───────────────────────────────────────

    @Test
    void thresholdCoupon_subtotalBelowThreshold_noDiscount() {
        stubThresholdCoupon("SAVE50", bd("500"), bd("50"));

        PricingResult r = engine.computePricing(null, bd("499.99"), bd("10"), "SAVE50", null);

        assertThat(r.getDiscountsTotal()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(r.getAppliedCouponCode()).isNull();
    }

    @Test
    void thresholdCoupon_subtotalAtThreshold_appliesFullDiscount() {
        stubThresholdCoupon("SAVE50", bd("500"), bd("50"));

        PricingResult r = engine.computePricing(null, bd("500.00"), bd("10"), "SAVE50", null);

        assertThat(r.getDiscountsTotal()).isEqualByComparingTo(bd("50"));
        assertThat(r.getAppliedCouponCode()).isEqualTo("SAVE50");
        assertThat(r.getTotalPayable()).isEqualByComparingTo(bd("460.00")); // 500-50+10
    }

    @Test
    void thresholdCoupon_subtotalAboveThreshold_appliesDiscount() {
        stubThresholdCoupon("SAVE50", bd("500"), bd("50"));

        PricingResult r = engine.computePricing(null, bd("800.00"), bd("0"), "SAVE50", null);

        assertThat(r.getDiscountsTotal()).isEqualByComparingTo(bd("50"));
    }

    // ── PERCENTAGE_DISCOUNT coupon ───────────────────────────────────────────

    @Test
    void percentageCoupon_computesCorrectly() {
        stubPercentageCoupon("PCT10", bd("10"), null); // 10%, no cap

        PricingResult r = engine.computePricing(null, bd("200.00"), bd("0"), "PCT10", null);

        assertThat(r.getDiscountsTotal()).isEqualByComparingTo(bd("20.00"));
    }

    @Test
    void percentageCoupon_cappedAtPerCouponMax() {
        stubPercentageCoupon("PCT10", bd("10"), bd("15")); // 10%, coupon cap=$15

        PricingResult r = engine.computePricing(null, bd("200.00"), bd("0"), "PCT10", null);

        // 10% of 200 = 20, but coupon cap=15
        assertThat(r.getDiscountsTotal()).isEqualByComparingTo(bd("15.00"));
    }

    @Test
    void percentageCoupon_cappedAtSystemMax25() {
        // 50% of $100 = $50, but system-wide cap is $25 per prompt
        stubPercentageCoupon("HALF", bd("50"), null); // 50%, no per-coupon cap

        PricingResult r = engine.computePricing(null, bd("100.00"), bd("0"), "HALF", null);

        assertThat(r.getDiscountsTotal()).isEqualByComparingTo(bd("25.00"));
    }

    @Test
    void percentageCoupon_perCouponCapLowerThanSystemCap_usesPerCouponCap() {
        // Per-coupon cap $10 is lower than system cap $25 → uses $10
        stubPercentageCoupon("PCT50", bd("50"), bd("10")); // 50%, coupon cap=$10

        PricingResult r = engine.computePricing(null, bd("100.00"), bd("0"), "PCT50", null);

        assertThat(r.getDiscountsTotal()).isEqualByComparingTo(bd("10.00"));
    }

    // ── SHIPPING_WAIVER coupon ───────────────────────────────────────────────

    @Test
    void shippingWaiver_aboveThreshold_waivedShipping() {
        stubShippingWaiverCoupon("FREESHIP", bd("200"));

        PricingResult r = engine.computePricing(null, bd("200.00"), bd("15"), "FREESHIP", null);

        assertThat(r.getShippingTotal()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(r.getTotalPayable()).isEqualByComparingTo(bd("200.00")); // no shipping
    }

    @Test
    void shippingWaiver_belowThreshold_shippingNotWaived() {
        stubShippingWaiverCoupon("FREESHIP", bd("200"));

        PricingResult r = engine.computePricing(null, bd("100.00"), bd("15"), "FREESHIP", null);

        assertThat(r.getShippingTotal()).isEqualByComparingTo(bd("15"));
    }

    // ── Discount cap at subtotal ─────────────────────────────────────────────

    @Test
    void totalDiscount_cappedAtSubtotal() {
        // threshold coupon gives $150 discount on a $100 order → cap at $100
        stubThresholdCoupon("BIG", bd("0"), bd("150"));

        PricingResult r = engine.computePricing(null, bd("100.00"), bd("10"), "BIG", null);

        assertThat(r.getDiscountsTotal()).isEqualByComparingTo(bd("100.00"));
        assertThat(r.getFinalSubtotal()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(r.getTotalPayable()).isEqualByComparingTo(bd("10.00")); // shipping still applies
    }

    // ── Campaign: SPEND_AND_GET ──────────────────────────────────────────────

    @Test
    void spendAndGet_belowSpendThreshold_noDiscount() {
        when(campaignRepository.findById(campaignId))
                .thenReturn(Optional.of(spendAndGetCampaign(bd("1000"), bd("200"))));

        PricingResult r = engine.computePricing(null, bd("999.99"), bd("0"), null, campaignId);

        assertThat(r.getAppliedCampaignId()).isNull();
        assertThat(r.getDiscountsTotal()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void spendAndGet_atSpendThreshold_getsReward() {
        when(campaignRepository.findById(campaignId))
                .thenReturn(Optional.of(spendAndGetCampaign(bd("1000"), bd("200"))));

        PricingResult r = engine.computePricing(null, bd("1000.00"), bd("0"), null, campaignId);

        assertThat(r.getAppliedCampaignId()).isEqualTo(campaignId);
        assertThat(r.getDiscountsTotal()).isEqualByComparingTo(bd("200.00"));
    }

    // ── Campaign: SECOND_ITEM_DISCOUNT ───────────────────────────────────────

    @Test
    void secondItemDiscount_calculatesCorrectly() {
        // 50% off half the subtotal = 50% of (200/2) = 50
        when(campaignRepository.findById(campaignId))
                .thenReturn(Optional.of(secondItemDiscountCampaign(bd("50"))));

        PricingResult r = engine.computePricing(null, bd("200.00"), bd("0"), null, campaignId);

        assertThat(r.getDiscountsTotal()).isEqualByComparingTo(bd("50.00"));
    }

    // ── Auto-campaign selection (Q6) ─────────────────────────────────────────

    @Test
    void autoSelectCampaign_picksHighestPriority() {
        Campaign low  = spendAndGetCampaign(bd("100"), bd("10")); low.setPriority(5);
        Campaign high = spendAndGetCampaign(bd("100"), bd("50")); high.setPriority(20);
        // Repository returns ordered by priority DESC (high first)
        when(campaignRepository.findActiveCampaignsByNow(any())).thenReturn(List.of(high, low));

        PricingResult r = engine.computePricing(null, bd("200.00"), bd("0"), null, null);

        assertThat(r.getDiscountsTotal()).isEqualByComparingTo(bd("50.00"));
    }

    @Test
    void autoSelectCampaign_noneEligible_noDiscount() {
        Campaign c = spendAndGetCampaign(bd("10000"), bd("200")); // threshold too high
        when(campaignRepository.findActiveCampaignsByNow(any())).thenReturn(List.of(c));

        PricingResult r = engine.computePricing(null, bd("100.00"), bd("0"), null, null);

        assertThat(r.getAppliedCampaignId()).isNull();
    }

    @Test
    void unknownCouponCode_returnsZeroDiscount() {
        when(couponRepository.findByCode("UNKNOWN")).thenReturn(Optional.empty());

        PricingResult r = engine.computePricing(null, bd("200.00"), bd("10"), "UNKNOWN", null);

        assertThat(r.getDiscountsTotal()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(r.getAppliedCouponCode()).isNull();
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private BigDecimal bd(String v) { return new BigDecimal(v); }

    private Member member(MemberStatus status) {
        return Member.builder()
                .id(UUID.randomUUID()).userId(buyerId)
                .status(status).tier(MemberTier.BRONZE).pointsBalance(0L).build();
    }

    private void stubThresholdCoupon(String code, BigDecimal threshold, BigDecimal amount) {
        Coupon c = Coupon.builder()
                .id(couponId).code(code)
                .type(CouponType.THRESHOLD_DISCOUNT)
                .thresholdAmount(threshold).discountAmount(amount)
                .isActive(true).priority(10).build();
        when(couponRepository.findByCode(code)).thenReturn(Optional.of(c));
    }

    private void stubPercentageCoupon(String code, BigDecimal pct, BigDecimal max) {
        Coupon c = Coupon.builder()
                .id(couponId).code(code)
                .type(CouponType.PERCENTAGE_DISCOUNT)
                .discountPercent(pct).maxDiscountAmount(max)
                .isActive(true).priority(10).build();
        when(couponRepository.findByCode(code)).thenReturn(Optional.of(c));
    }

    private void stubShippingWaiverCoupon(String code, BigDecimal threshold) {
        Coupon c = Coupon.builder()
                .id(couponId).code(code)
                .type(CouponType.SHIPPING_WAIVER)
                .thresholdAmount(threshold)
                .isActive(true).priority(5).build();
        when(couponRepository.findByCode(code)).thenReturn(Optional.of(c));
    }

    private Campaign spendAndGetCampaign(BigDecimal threshold, BigDecimal reward) {
        Campaign c = new Campaign();
        c.setId(campaignId);
        c.setName("Spend & Get");
        c.setType(CampaignType.SPEND_AND_GET);
        c.setParams("{\"spendThreshold\":" + threshold.toPlainString()
                + ",\"rewardAmount\":" + reward.toPlainString() + "}");
        c.setActive(true);
        c.setPriority(10);
        return c;
    }

    private Campaign secondItemDiscountCampaign(BigDecimal pct) {
        Campaign c = new Campaign();
        c.setId(campaignId);
        c.setName("2nd Item Discount");
        c.setType(CampaignType.SECOND_ITEM_DISCOUNT);
        c.setParams("{\"discountPercent\":" + pct.toPlainString() + "}");
        c.setActive(true);
        c.setPriority(10);
        return c;
    }
}
