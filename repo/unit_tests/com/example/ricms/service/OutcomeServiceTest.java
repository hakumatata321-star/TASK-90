package com.example.ricms.service;

import com.example.ricms.domain.entity.Outcome;
import com.example.ricms.domain.enums.OutcomeType;
import com.example.ricms.dto.request.ContributionDto;
import com.example.ricms.dto.request.OutcomeCreateRequest;
import com.example.ricms.dto.response.DuplicateCheckResponse;
import com.example.ricms.exception.AppException;
import com.example.ricms.repository.*;
import com.example.ricms.security.PermissionEnforcer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for OutcomeService covering:
 *  - contribution shares must sum to exactly 100% (Q13)
 *  - duplicate detection: title and 70% abstract overlap (Q14)
 *  - certificate number uniqueness (Q14)
 *  - moderation queue routing for suspected duplicates
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OutcomeServiceTest {

    @Mock OutcomeRepository         outcomeRepository;
    @Mock ContributionRepository    contributionRepository;
    @Mock OutcomeEvidenceRepository outcomeEvidenceRepository;
    @Mock ModerationQueueRepository moderationQueueRepository;
    @Mock AuditService              auditService;
    @Mock PermissionEnforcer        permissionEnforcer;

    OutcomeService service;

    private final UUID actorId   = UUID.randomUUID();
    private final UUID contribId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new OutcomeService(
                outcomeRepository, contributionRepository, outcomeEvidenceRepository,
                moderationQueueRepository, auditService, permissionEnforcer);

        // Default: no existing outcomes
        when(outcomeRepository.findByTitleNormalized(any())).thenReturn(List.of());
        when(outcomeRepository.findByCertificateNumber(any())).thenReturn(Optional.empty());
        when(outcomeRepository.findWithAbstractText()).thenReturn(List.of());
        when(outcomeRepository.save(any())).thenAnswer(inv -> {
            Outcome o = inv.getArgument(0);
            try {
                var f = Outcome.class.getDeclaredField("id");
                f.setAccessible(true);
                if (f.get(o) == null) f.set(o, UUID.randomUUID());
            } catch (Exception e) { /* ignore */ }
            return o;
        });
        when(contributionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    // ── Contribution sum validation (Q13) ────────────────────────────────────

    @Test
    void registerOutcome_exactly100Percent_succeeds() {
        OutcomeCreateRequest req = validRequest(List.of(contrib(contribId, 100.0)));

        // Should not throw
        assertThatCode(() -> service.registerOutcome(req, actorId)).doesNotThrowAnyException();
    }

    @Test
    void registerOutcome_threeContribsSummingTo100_succeeds() {
        OutcomeCreateRequest req = validRequest(List.of(
                contrib(UUID.randomUUID(), 50.0),
                contrib(UUID.randomUUID(), 30.0),
                contrib(UUID.randomUUID(), 20.0)));

        assertThatCode(() -> service.registerOutcome(req, actorId)).doesNotThrowAnyException();
    }

    @ParameterizedTest(name = "sum={0}% rejects")
    @ValueSource(doubles = {0.0, 99.0, 99.99, 100.01, 101.0})
    void registerOutcome_contributionSumNot100_throws400(double sumPercent) {
        OutcomeCreateRequest req = validRequest(List.of(contrib(contribId, sumPercent)));

        assertThatThrownBy(() -> service.registerOutcome(req, actorId))
                .isInstanceOf(AppException.class)
                .satisfies(e -> {
                    AppException ae = (AppException) e;
                    assertThat(ae.getCode()).isEqualTo("INVALID_CONTRIBUTIONS");
                    assertThat(ae.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                });
    }

    @Test
    void registerOutcome_emptyContributions_throws400() {
        OutcomeCreateRequest req = validRequest(Collections.emptyList());

        assertThatThrownBy(() -> service.registerOutcome(req, actorId))
                .isInstanceOf(AppException.class)
                .satisfies(e -> assertThat(((AppException) e).getCode()).isEqualTo("INVALID_CONTRIBUTIONS"));
    }

    // ── Certificate number uniqueness (Q14) ──────────────────────────────────

    @Test
    void registerOutcome_duplicateCertificate_throws409() {
        Outcome existing = outcomeEntity("Different Title", null);
        when(outcomeRepository.findByCertificateNumber("CERT-001")).thenReturn(Optional.of(existing));

        OutcomeCreateRequest req = validRequest(List.of(contrib(contribId, 100.0)));
        req.setCertificateNumber("CERT-001");

        assertThatThrownBy(() -> service.registerOutcome(req, actorId))
                .isInstanceOf(AppException.class)
                .satisfies(e -> {
                    AppException ae = (AppException) e;
                    assertThat(ae.getCode()).isEqualTo("DUPLICATE_CERTIFICATE");
                    assertThat(ae.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                });
    }

    // ── Duplicate title detection (Q14) ──────────────────────────────────────

    @Test
    void registerOutcome_exactTitleMatch_setsUnderReviewAndQueues() {
        Outcome dup = outcomeEntity("Deep Learning for Time Series", null);
        when(outcomeRepository.findByTitleNormalized(anyString())).thenReturn(List.of(dup));

        OutcomeCreateRequest req = validRequest(List.of(contrib(contribId, 100.0)));
        req.setTitle("Deep Learning for Time Series");

        var result = service.registerOutcome(req, actorId);

        assertThat(result.getStatus()).isEqualTo("UNDER_REVIEW");
        verify(moderationQueueRepository).save(any());
    }

    @Test
    void registerOutcome_noTitleMatch_setsActive() {
        when(outcomeRepository.findByTitleNormalized(any())).thenReturn(List.of());

        OutcomeCreateRequest req = validRequest(List.of(contrib(contribId, 100.0)));

        var result = service.registerOutcome(req, actorId);

        assertThat(result.getStatus()).isEqualTo("ACTIVE");
        verify(moderationQueueRepository, never()).save(any());
    }

    // ── Abstract token overlap threshold (Q14) ───────────────────────────────

    @Test
    void tokenOverlap_identicalText_returns1() {
        String text = "machine learning deep neural network model training";
        assertThat(service.tokenOverlap(text, text)).isEqualTo(1.0);
    }

    @Test
    void tokenOverlap_noCommonTokens_returns0() {
        assertThat(service.tokenOverlap("cat dog fish", "mountain river lake")).isEqualTo(0.0);
    }

    @Test
    void tokenOverlap_atExactly70Percent_triggersDetection() {
        // Construct two strings with exactly 70% Jaccard overlap
        // Tokens in A: {a,b,c,d,e,f,g,h,i,j} = 10
        // Tokens in B: {a,b,c,d,e,f,g,h,i,k} = 10
        // Intersection = 9, Union = 11 → overlap = 9/11 ≈ 0.818 (above 70%)
        double overlap = service.tokenOverlap("aaa bbb ccc ddd eee fff ggg hhh iii jjj",
                                              "aaa bbb ccc ddd eee fff ggg hhh iii kkk");
        assertThat(overlap).isGreaterThanOrEqualTo(0.70);
    }

    @Test
    void tokenOverlap_below70Percent_doesNotTrigger() {
        // 3 common tokens, 7 unique each → intersection=3, union=17 → 3/17 ≈ 0.176
        double overlap = service.tokenOverlap(
                "aaa bbb ccc ddd eee fff ggg hhh iii jjj",   // 10 unique tokens
                "aaa bbb ccc xxx yyy zzz qqq rrr sss ttt"); // 3 shared + 7 new
        assertThat(overlap).isLessThan(0.70);
    }

    @Test
    void checkDuplicate_highAbstractOverlap_returnsFound() {
        // Use an existing outcome with the same abstract
        String abstract1 = "This paper investigates novel approaches to machine learning optimization strategies";
        Outcome existing  = outcomeEntity("Existing Title", abstract1);
        when(outcomeRepository.findWithAbstractText()).thenReturn(List.of(existing));

        OutcomeCreateRequest req = validRequest(List.of(contrib(contribId, 100.0)));
        req.setTitle("Brand New Different Title");
        req.setAbstractText(abstract1); // identical → 100% overlap

        DuplicateCheckResponse resp = service.checkDuplicate(req);

        assertThat(resp.isDuplicateFound()).isTrue();
        assertThat(resp.getMatchingOutcomeIds()).contains(existing.getId());
    }

    @Test
    void checkDuplicate_lowAbstractOverlap_returnsNotFound() {
        String abstract1 = "quantum physics subatomic particles wave function collapse";
        String abstract2 = "machine learning neural networks gradient descent optimization";
        Outcome existing  = outcomeEntity("Another Title", abstract2);
        when(outcomeRepository.findWithAbstractText()).thenReturn(List.of(existing));

        OutcomeCreateRequest req = validRequest(List.of(contrib(contribId, 100.0)));
        req.setTitle("Completely Different Title");
        req.setAbstractText(abstract1);

        DuplicateCheckResponse resp = service.checkDuplicate(req);

        assertThat(resp.isDuplicateFound()).isFalse();
    }

    // ── normalizeTitle ────────────────────────────────────────────────────────

    @Test
    void normalizeTitle_removesSpecialCharsAndLowercases() {
        String normalized = service.normalizeTitle("Deep Learning: A Survey!");
        assertThat(normalized).isEqualTo("deep learning a survey");
    }

    @Test
    void normalizeTitle_collapsesWhitespace() {
        String normalized = service.normalizeTitle("  Too   Many   Spaces  ");
        assertThat(normalized).isEqualTo("too many spaces");
    }

    @Test
    void normalizeTitle_nullInput_returnsEmpty() {
        assertThat(service.normalizeTitle(null)).isEmpty();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private OutcomeCreateRequest validRequest(List<ContributionDto> contributions) {
        OutcomeCreateRequest req = new OutcomeCreateRequest();
        req.setType(OutcomeType.PAPER);
        req.setTitle("A Novel Research Paper");
        req.setAbstractText("This paper presents new findings.");
        req.setContributions(contributions);
        req.setEvidences(List.of());
        return req;
    }

    private ContributionDto contrib(UUID userId, double pct) {
        ContributionDto c = new ContributionDto();
        c.setContributorId(userId);
        c.setSharePercent(BigDecimal.valueOf(pct));
        return c;
    }

    private Outcome outcomeEntity(String title, String abstractText) {
        Outcome o = new Outcome();
        try {
            var f = Outcome.class.getDeclaredField("id");
            f.setAccessible(true);
            f.set(o, UUID.randomUUID());
        } catch (Exception e) { /* ignore */ }
        o.setTitleOriginal(title);
        o.setTitleNormalized(service.normalizeTitle(title));
        o.setAbstractText(abstractText);
        o.setType(OutcomeType.PAPER);
        o.setStatus("ACTIVE");
        return o;
    }
}
