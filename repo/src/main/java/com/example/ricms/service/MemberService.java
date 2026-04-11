package com.example.ricms.service;

import com.example.ricms.domain.entity.Member;
import com.example.ricms.domain.entity.PointsLedger;
import com.example.ricms.domain.enums.MemberStatus;
import com.example.ricms.domain.enums.MemberTier;
import com.example.ricms.dto.response.MemberResponse;
import com.example.ricms.dto.response.PageResponse;
import com.example.ricms.dto.response.PointsLedgerResponse;
import com.example.ricms.exception.AppException;
import com.example.ricms.repository.MemberRepository;
import com.example.ricms.repository.PointsLedgerRepository;
import com.example.ricms.security.SecurityUtils;
import lombok.RequiredArgsConstructor;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class MemberService {

    private final MemberRepository memberRepository;
    private final PointsLedgerRepository pointsLedgerRepository;
    private final AuditService auditService;

    // ------------------------------------------------------------------
    // Profile & ledger (Q3 / Q4)
    // ------------------------------------------------------------------

    public MemberResponse getMyProfile(UUID userId) {
        Member member = requireByUserId(userId);
        return toResponse(member);
    }

    public PageResponse<PointsLedgerResponse> getPointsLedger(UUID userId, int page, int pageSize) {
        // RLS: non-admin callers can only view their own ledger
        if (!SecurityUtils.hasPermission("ADMIN", "READ")) {
            userId = SecurityUtils.getCurrentUserId();
        }
        Member member = requireByUserId(userId);
        var ledgerPage = pointsLedgerRepository.findByMemberIdOrderByCreatedAtDesc(
                member.getId(), PageRequest.of(page, pageSize));
        return PageResponse.of(ledgerPage.map(this::toLedgerResponse));
    }

    // ------------------------------------------------------------------
    // Points accrual (Q4)
    // Points = floor(totalPayable) — 1 point per whole dollar after discounts.
    // Only persisted; the caller (OrderService) is responsible for calling
    // this at CONFIRMED_PAYMENT / COMPLETED state.
    // ------------------------------------------------------------------

    @Transactional
    public void accruePoints(UUID memberId, UUID orderId, BigDecimal payableAmount) {
        Member member = memberRepository.findById(memberId)
                .orElseThrow(() -> new AppException("MEMBER_NOT_FOUND", "Member not found", HttpStatus.NOT_FOUND));

        // 1 point per $1.00 – floor to the nearest whole dollar (Q4)
        long pointsEarned = payableAmount
                .setScale(0, RoundingMode.FLOOR)
                .longValue();

        if (pointsEarned <= 0) return;

        PointsLedger ledger = PointsLedger.builder()
                .memberId(memberId)
                .pointsDelta(pointsEarned)
                .sourceOrderId(orderId)
                .currencyAmountBasis(payableAmount)
                .description("Points earned for order " + orderId)
                .build();
        pointsLedgerRepository.save(ledger);

        member.setPointsBalance(member.getPointsBalance() + pointsEarned);
        checkAndUpgradeTier(member);   // immediate upgrade (Q5)
        memberRepository.save(member);
    }

    // ------------------------------------------------------------------
    // Tier management (Q5)
    // ------------------------------------------------------------------

    /**
     * Upgrade tier immediately whenever the running balance crosses a threshold.
     * Only ever promotes – downgrade is handled by the scheduled monthly job.
     */
    public void checkAndUpgradeTier(Member member) {
        MemberTier target = tierForBalance(member.getPointsBalance());
        if (target.ordinal() > member.getTier().ordinal()) {
            MemberTier before = member.getTier();
            log.info("Upgrading member {} tier: {} → {}", member.getId(), before, target);
            member.setTier(target);
            auditService.record(null, "MEMBER", member.getId().toString(),
                    "TIER_CHANGE", "SYSTEM_TIER_UPGRADE",
                    Map.of("tier", before.name()),
                    Map.of("tier", target.name()));
        }
        // Intentionally no downgrade here – that is the monthly job's responsibility (Q5)
    }

    /**
     * Monthly scheduled job: recalculate each member's tier from their
     * authoritative ledger sum and only LOWER the tier if warranted (Q5).
     * Upgrades are already handled immediately at accrual time.
     */
    @Transactional
    public void monthlyTierDowngrade() {
        log.info("Running monthly tier downgrade job");
        List<Member> allMembers = memberRepository.findAll();
        int downgraded = 0;
        for (Member member : allMembers) {
            // Authoritative balance from ledger (handles any manual adjustments)
            Long ledgerTotal = pointsLedgerRepository.sumPointsByMemberId(member.getId());
            long authoritative = ledgerTotal != null ? ledgerTotal : 0L;

            // Sync the denormalised balance field with the ledger sum
            member.setPointsBalance(authoritative);

            MemberTier correct = tierForBalance(authoritative);
            if (correct.ordinal() < member.getTier().ordinal()) {
                // Only downgrade – never promote during this job (Q5)
                MemberTier before = member.getTier();
                log.info("Downgrading member {} tier: {} → {}", member.getId(), before, correct);
                member.setTier(correct);
                downgraded++;
                auditService.record(null, "MEMBER", member.getId().toString(),
                        "TIER_CHANGE", "SYSTEM_TIER_DOWNGRADE",
                        Map.of("tier", before.name()),
                        Map.of("tier", correct.name()));
            }
        }
        memberRepository.saveAll(allMembers);
        log.info("Monthly tier downgrade complete. {} member(s) downgraded.", downgraded);
    }

    private MemberTier tierForBalance(long balance) {
        if (balance >= 5000) return MemberTier.GOLD;
        if (balance >= 1000) return MemberTier.SILVER;
        return MemberTier.BRONZE;
    }

    // ------------------------------------------------------------------
    // Admin status management (Q3)
    // ------------------------------------------------------------------

    /**
     * Update a member's standing status (ACTIVE / SUSPENDED / DELINQUENT).
     * Member pricing is gated on ACTIVE status (Q3), so changing status
     * immediately affects pricing eligibility on subsequent orders.
     */
    @Transactional
    public MemberResponse updateStatus(UUID memberId, MemberStatus newStatus, UUID actorId) {
        Member member = memberRepository.findById(memberId)
                .orElseThrow(() -> new AppException("MEMBER_NOT_FOUND", "Member not found", HttpStatus.NOT_FOUND));

        MemberStatus previous = member.getStatus();
        member.setStatus(newStatus);

        // Record when the member entered good standing
        if (newStatus == MemberStatus.ACTIVE && previous != MemberStatus.ACTIVE) {
            member.setGoodStandingSince(OffsetDateTime.now());
        }

        memberRepository.save(member);
        log.info("Member {} status changed by actor {}: {} → {}", memberId, actorId, previous, newStatus);
        auditService.record(actorId, "MEMBER", memberId.toString(),
                "MEMBER_STATUS_CHANGE", "ADMIN_ACTION",
                Map.of("status", previous.name()),
                Map.of("status", newStatus.name()));
        return toResponse(member);
    }

    /**
     * Create a member profile for a new user.
     * Called automatically by UserService.createUser so every user starts
     * with a BRONZE / ACTIVE membership.
     */
    @Transactional
    public Member createForUser(UUID userId) {
        // Idempotent: return existing if already created
        Optional<Member> existing = memberRepository.findByUserId(userId);
        if (existing.isPresent()) return existing.get();

        Member member = Member.builder()
                .userId(userId)
                .status(MemberStatus.ACTIVE)
                .tier(MemberTier.BRONZE)
                .pointsBalance(0L)
                .goodStandingSince(OffsetDateTime.now())
                .build();
        return memberRepository.save(member);
    }

    // ------------------------------------------------------------------
    // Mapping helpers
    // ------------------------------------------------------------------

    public MemberResponse toResponse(Member member) {
        return MemberResponse.builder()
                .memberId(member.getId())
                .userId(member.getUserId())
                .status(member.getStatus())
                .tier(member.getTier())
                .pointsBalance(member.getPointsBalance())
                .build();
    }

    private PointsLedgerResponse toLedgerResponse(PointsLedger p) {
        return PointsLedgerResponse.builder()
                .id(p.getId())
                .pointsDelta(p.getPointsDelta())
                .sourceOrderId(p.getSourceOrderId())
                .currencyAmountBasis(p.getCurrencyAmountBasis())
                .description(p.getDescription())
                .createdAt(p.getCreatedAt())
                .build();
    }

    private Member requireByUserId(UUID userId) {
        return memberRepository.findByUserId(userId)
                .orElseThrow(() -> new AppException(
                        "MEMBER_NOT_FOUND", "Member profile not found", HttpStatus.NOT_FOUND));
    }
}
