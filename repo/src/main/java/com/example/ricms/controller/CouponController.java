package com.example.ricms.controller;

import com.example.ricms.domain.entity.Coupon;
import com.example.ricms.domain.enums.CouponType;
import com.example.ricms.dto.request.CouponValidateRequest;
import com.example.ricms.dto.response.CouponValidateResponse;
import com.example.ricms.repository.CouponRepository;
import com.example.ricms.service.PricingEngine;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.Optional;

/**
 * POST /v1/coupons/validate
 *
 * Returns the exact discount a specific coupon code would produce for the
 * supplied order context, or an explanation of why it is not applicable.
 * This is a read-only preview; it does not reserve or consume the coupon.
 */
@RestController
@RequestMapping("/v1/coupons")
@RequiredArgsConstructor
public class CouponController {

    private final CouponRepository couponRepository;
    private final PricingEngine pricingEngine;

    @PostMapping("/validate")
    public ResponseEntity<CouponValidateResponse> validate(
            @RequestBody CouponValidateRequest request) {

        String code     = request.getCouponCode();
        BigDecimal sub  = contextSubtotal(request);
        OffsetDateTime now = OffsetDateTime.now();

        if (code == null || code.isBlank()) {
            return ResponseEntity.ok(reject(null, "Coupon code is required"));
        }

        Optional<Coupon> opt = couponRepository.findByCode(code.toUpperCase().trim());
        if (opt.isEmpty()) {
            return ResponseEntity.ok(reject(code, "Coupon code not found"));
        }

        Coupon c = opt.get();
        if (!c.isActive()) {
            return ResponseEntity.ok(reject(code, "Coupon is inactive"));
        }
        if (c.getActiveFrom() != null && c.getActiveFrom().isAfter(now)) {
            return ResponseEntity.ok(reject(code, "Coupon has not started yet"));
        }
        if (c.getActiveTo() != null && c.getActiveTo().isBefore(now)) {
            return ResponseEntity.ok(reject(code, "Coupon has expired"));
        }

        // Compute discount for this specific coupon type
        return switch (c.getType()) {
            case THRESHOLD_DISCOUNT -> {
                BigDecimal threshold = orZero(c.getThresholdAmount());
                if (sub.compareTo(threshold) < 0) {
                    yield ResponseEntity.ok(reject(code,
                            "Order subtotal $" + sub.toPlainString() +
                            " below threshold $" + threshold.toPlainString()));
                }
                yield ResponseEntity.ok(accept(c, orZero(c.getDiscountAmount()), false));
            }
            case PERCENTAGE_DISCOUNT -> {
                BigDecimal pct      = orZero(c.getDiscountPercent());
                BigDecimal computed = sub.multiply(pct)
                        .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
                if (c.getMaxDiscountAmount() != null && computed.compareTo(c.getMaxDiscountAmount()) > 0) {
                    computed = c.getMaxDiscountAmount();
                }
                yield ResponseEntity.ok(accept(c, computed, false));
            }
            case SHIPPING_WAIVER -> {
                BigDecimal threshold = orZero(c.getThresholdAmount());
                if (threshold.compareTo(BigDecimal.ZERO) > 0 && sub.compareTo(threshold) < 0) {
                    yield ResponseEntity.ok(reject(code,
                            "Order subtotal below shipping waiver threshold $" + threshold.toPlainString()));
                }
                yield ResponseEntity.ok(accept(c, BigDecimal.ZERO, true));
            }
        };
    }

    // ------------------------------------------------------------------

    private BigDecimal contextSubtotal(CouponValidateRequest req) {
        if (req.getOrderContext() != null && req.getOrderContext().getSubtotal() != null)
            return req.getOrderContext().getSubtotal();
        return BigDecimal.ZERO;
    }

    private BigDecimal orZero(BigDecimal v) {
        return v != null ? v : BigDecimal.ZERO;
    }

    private CouponValidateResponse accept(Coupon c, BigDecimal amount, boolean waivedShipping) {
        return CouponValidateResponse.builder()
                .isValid(true)
                .couponId(c.getId())
                .couponCode(c.getCode())
                .couponType(c.getType().name())
                .discountAmount(amount)
                .currency("USD")
                .waivedShipping(waivedShipping)
                .build();
    }

    private CouponValidateResponse reject(String code, String reason) {
        return CouponValidateResponse.builder()
                .isValid(false)
                .couponCode(code)
                .discountAmount(BigDecimal.ZERO)
                .currency("USD")
                .reason(reason)
                .build();
    }
}
