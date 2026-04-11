package com.example.ricms.service;

import com.example.ricms.domain.entity.Member;
import com.example.ricms.domain.entity.PointsLedger;
import com.example.ricms.domain.enums.MemberStatus;
import com.example.ricms.domain.enums.MemberTier;
import com.example.ricms.dto.response.MemberResponse;
import com.example.ricms.repository.MemberRepository;
import com.example.ricms.repository.PointsLedgerRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
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
 * Unit tests for MemberService covering:
 *  - points floor calculation (Q4)
 *  - tier boundary transitions (Q5) – immediate upgrade, monthly downgrade
 *  - status transitions and goodStandingSince tracking (Q3)
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MemberServiceTest {

    @Mock MemberRepository memberRepository;
    @Mock PointsLedgerRepository pointsLedgerRepository;
    @Mock AuditService auditService;

    MemberService service;

    private final UUID memberId = UUID.randomUUID();
    private final UUID userId   = UUID.randomUUID();
    private final UUID orderId  = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new MemberService(memberRepository, pointsLedgerRepository, auditService);
    }

    // ── Points accrual floor calculation (Q4) ────────────────────────────────

    @Test
    void accruePoints_floorOf9_99_gives9Points() {
        Member m = buildMember(MemberTier.BRONZE, 0L);
        when(memberRepository.findById(memberId)).thenReturn(Optional.of(m));

        service.accruePoints(memberId, orderId, new BigDecimal("9.99"));

        ArgumentCaptor<PointsLedger> cap = ArgumentCaptor.forClass(PointsLedger.class);
        verify(pointsLedgerRepository).save(cap.capture());
        assertThat(cap.getValue().getPointsDelta()).isEqualTo(9L);
    }

    @Test
    void accruePoints_exactDollar_givesExactPoints() {
        Member m = buildMember(MemberTier.BRONZE, 0L);
        when(memberRepository.findById(memberId)).thenReturn(Optional.of(m));

        service.accruePoints(memberId, orderId, new BigDecimal("10.00"));

        ArgumentCaptor<PointsLedger> cap = ArgumentCaptor.forClass(PointsLedger.class);
        verify(pointsLedgerRepository).save(cap.capture());
        assertThat(cap.getValue().getPointsDelta()).isEqualTo(10L);
    }

    @Test
    void accruePoints_zeroPayable_noLedgerEntry() {
        Member m = buildMember(MemberTier.BRONZE, 0L);
        when(memberRepository.findById(memberId)).thenReturn(Optional.of(m));

        service.accruePoints(memberId, orderId, BigDecimal.ZERO);

        verify(pointsLedgerRepository, never()).save(any());
    }

    @Test
    void accruePoints_fractionalCents_floorApplied() {
        // $99.01 → floor = 99 points (not round to 100)
        Member m = buildMember(MemberTier.BRONZE, 0L);
        when(memberRepository.findById(memberId)).thenReturn(Optional.of(m));

        service.accruePoints(memberId, orderId, new BigDecimal("99.01"));

        ArgumentCaptor<PointsLedger> cap = ArgumentCaptor.forClass(PointsLedger.class);
        verify(pointsLedgerRepository).save(cap.capture());
        assertThat(cap.getValue().getPointsDelta()).isEqualTo(99L);
    }

    // ── Tier thresholds (Q5) ─────────────────────────────────────────────────

    @ParameterizedTest(name = "balance={0} → tier={1}")
    @CsvSource({
        "0,     BRONZE",
        "999,   BRONZE",
        "1000,  SILVER",
        "4999,  SILVER",
        "5000,  GOLD",
        "99999, GOLD"
    })
    void checkAndUpgradeTier_tierBoundaries(long balance, String expectedTier) {
        Member m = buildMember(MemberTier.BRONZE, balance);

        service.checkAndUpgradeTier(m);

        assertThat(m.getTier().name()).isEqualTo(expectedTier);
    }

    @Test
    void checkAndUpgradeTier_bronzeWith1000Points_upgradestoSilver() {
        Member m = buildMember(MemberTier.BRONZE, 1000L);
        service.checkAndUpgradeTier(m);
        assertThat(m.getTier()).isEqualTo(MemberTier.SILVER);
    }

    @Test
    void checkAndUpgradeTier_silverWith5000Points_upgradesToGold() {
        Member m = buildMember(MemberTier.SILVER, 5000L);
        service.checkAndUpgradeTier(m);
        assertThat(m.getTier()).isEqualTo(MemberTier.GOLD);
    }

    @Test
    void checkAndUpgradeTier_goldWith999Points_doesNotDowngrade() {
        // upgrade-only method: balance below threshold must NOT demote
        Member m = buildMember(MemberTier.GOLD, 999L);
        service.checkAndUpgradeTier(m);
        assertThat(m.getTier()).isEqualTo(MemberTier.GOLD);
    }

    @Test
    void checkAndUpgradeTier_silverWith999Points_doesNotDowngrade() {
        Member m = buildMember(MemberTier.SILVER, 999L);
        service.checkAndUpgradeTier(m);
        assertThat(m.getTier()).isEqualTo(MemberTier.SILVER);
    }

    // ── Monthly downgrade job (Q5) ───────────────────────────────────────────

    @Test
    void monthlyTierDowngrade_silverWith999LedgerBalance_downgradesToBronze() {
        Member m = buildMember(MemberTier.SILVER, 1500L);
        when(memberRepository.findAll()).thenReturn(List.of(m));
        // Authoritative ledger says only 999 points
        when(pointsLedgerRepository.sumPointsByMemberId(m.getId())).thenReturn(999L);

        service.monthlyTierDowngrade();

        assertThat(m.getTier()).isEqualTo(MemberTier.BRONZE);
        assertThat(m.getPointsBalance()).isEqualTo(999L); // synced from ledger
        verify(memberRepository).saveAll(any());
    }

    @Test
    void monthlyTierDowngrade_goldWith4999LedgerBalance_downgradesToSilver() {
        Member m = buildMember(MemberTier.GOLD, 9999L);
        when(memberRepository.findAll()).thenReturn(List.of(m));
        when(pointsLedgerRepository.sumPointsByMemberId(m.getId())).thenReturn(4999L);

        service.monthlyTierDowngrade();

        assertThat(m.getTier()).isEqualTo(MemberTier.SILVER);
    }

    @Test
    void monthlyTierDowngrade_doesNotUpgrade() {
        // Member is BRONZE but ledger says 1000+ points → job must NOT promote
        Member m = buildMember(MemberTier.BRONZE, 0L);
        when(memberRepository.findAll()).thenReturn(List.of(m));
        when(pointsLedgerRepository.sumPointsByMemberId(m.getId())).thenReturn(5000L);

        service.monthlyTierDowngrade();

        // Balance updated, but tier stays BRONZE (no promotion in downgrade job)
        assertThat(m.getTier()).isEqualTo(MemberTier.BRONZE);
        assertThat(m.getPointsBalance()).isEqualTo(5000L);
    }

    @Test
    void monthlyTierDowngrade_nullLedgerSum_treatedAsZero() {
        Member m = buildMember(MemberTier.SILVER, 1000L);
        when(memberRepository.findAll()).thenReturn(List.of(m));
        when(pointsLedgerRepository.sumPointsByMemberId(m.getId())).thenReturn(null);

        service.monthlyTierDowngrade();

        assertThat(m.getTier()).isEqualTo(MemberTier.BRONZE);
        assertThat(m.getPointsBalance()).isEqualTo(0L);
    }

    // ── Status update (Q3) ───────────────────────────────────────────────────

    @Test
    void updateStatus_fromSuspendedToActive_setsGoodStandingSince() {
        Member m = buildMember(MemberTier.BRONZE, 0L);
        m.setStatus(MemberStatus.SUSPENDED);
        m.setGoodStandingSince(null);
        when(memberRepository.findById(memberId)).thenReturn(Optional.of(m));

        service.updateStatus(memberId, MemberStatus.ACTIVE, userId);

        assertThat(m.getStatus()).isEqualTo(MemberStatus.ACTIVE);
        assertThat(m.getGoodStandingSince()).isNotNull();
    }

    @Test
    void updateStatus_activeToActive_doesNotResetGoodStandingSince() {
        Member m = buildMember(MemberTier.BRONZE, 0L);
        m.setStatus(MemberStatus.ACTIVE);
        OffsetDateTime original = OffsetDateTime.now().minusDays(30);
        m.setGoodStandingSince(original);
        when(memberRepository.findById(memberId)).thenReturn(Optional.of(m));

        service.updateStatus(memberId, MemberStatus.ACTIVE, userId);

        // ACTIVE → ACTIVE: goodStandingSince must not be overwritten
        assertThat(m.getGoodStandingSince()).isEqualTo(original);
    }

    @Test
    void updateStatus_toSuspended_doesNotSetGoodStandingSince() {
        Member m = buildMember(MemberTier.BRONZE, 0L);
        m.setStatus(MemberStatus.ACTIVE);
        m.setGoodStandingSince(null);
        when(memberRepository.findById(memberId)).thenReturn(Optional.of(m));

        service.updateStatus(memberId, MemberStatus.SUSPENDED, userId);

        assertThat(m.getStatus()).isEqualTo(MemberStatus.SUSPENDED);
        assertThat(m.getGoodStandingSince()).isNull();
    }

    // ── createForUser idempotency ────────────────────────────────────────────

    @Test
    void createForUser_existingMember_returnsExistingWithoutSave() {
        Member existing = buildMember(MemberTier.SILVER, 2000L);
        existing.setUserId(userId);
        when(memberRepository.findByUserId(userId)).thenReturn(Optional.of(existing));

        Member result = service.createForUser(userId);

        assertThat(result).isSameAs(existing);
        verify(memberRepository, never()).save(any());
    }

    @Test
    void createForUser_noExistingMember_createsActiveBronzeMember() {
        when(memberRepository.findByUserId(userId)).thenReturn(Optional.empty());
        when(memberRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        Member result = service.createForUser(userId);

        assertThat(result.getStatus()).isEqualTo(MemberStatus.ACTIVE);
        assertThat(result.getTier()).isEqualTo(MemberTier.BRONZE);
        assertThat(result.getPointsBalance()).isEqualTo(0L);
        assertThat(result.getGoodStandingSince()).isNotNull();
    }

    // ── Helper ───────────────────────────────────────────────────────────────

    private Member buildMember(MemberTier tier, long balance) {
        return Member.builder()
                .id(memberId).userId(userId)
                .status(MemberStatus.ACTIVE)
                .tier(tier)
                .pointsBalance(balance)
                .build();
    }
}
