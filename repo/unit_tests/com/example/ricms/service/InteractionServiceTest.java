package com.example.ricms.service;

import com.example.ricms.config.AppProperties;
import com.example.ricms.domain.entity.Blacklist;
import com.example.ricms.domain.entity.Comment;
import com.example.ricms.domain.entity.ModerationQueue;
import com.example.ricms.domain.entity.OperationalParam;
import com.example.ricms.dto.request.CommentCreateRequest;
import com.example.ricms.dto.request.ReportRequest;
import com.example.ricms.dto.response.CommentResponse;
import com.example.ricms.exception.AppException;
import com.example.ricms.exception.RateLimitException;
import com.example.ricms.repository.*;
import com.example.ricms.security.PermissionEnforcer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpStatus;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for InteractionService covering:
 *  - rate limit hard reject with RateLimitException (Q15)
 *  - blacklist enforcement
 *  - sensitive-word moderation queue routing
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class InteractionServiceTest {

    @Mock CommentRepository         commentRepository;
    @Mock LikeRepository            likeRepository;
    @Mock FavoriteRepository        favoriteRepository;
    @Mock ReportRepository          reportRepository;
    @Mock ModerationQueueRepository moderationQueueRepository;
    @Mock BlacklistRepository       blacklistRepository;
    @Mock OperationalParamRepository operationalParamRepository;
    @Mock PermissionEnforcer        permissionEnforcer;

    AppProperties appProperties;
    InteractionService service;

    private final UUID userId    = UUID.randomUUID();
    private final UUID contentId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        appProperties = new AppProperties();
        // Default: 30 comments/hour, 10 reports/day (AppProperties defaults)

        service = new InteractionService(
                commentRepository, likeRepository, favoriteRepository,
                reportRepository, moderationQueueRepository, blacklistRepository,
                operationalParamRepository, appProperties, permissionEnforcer);

        // Default: user not blacklisted
        when(blacklistRepository.findActiveByUserId(eq(userId), any())).thenReturn(Optional.empty());
        // Default: no sensitive words configured
        when(operationalParamRepository.findById("sensitive_words")).thenReturn(Optional.empty());
        // Default: save echoes argument
        when(commentRepository.save(any())).thenAnswer(inv -> {
            Comment c = inv.getArgument(0);
            try {
                var f = Comment.class.getDeclaredField("id");
                f.setAccessible(true);
                if (f.get(c) == null) f.set(c, UUID.randomUUID());
            } catch (Exception e) { /* ignore */ }
            return c;
        });
    }

    // ── Blacklist enforcement ─────────────────────────────────────────────────

    @Test
    void createComment_blacklistedUser_throws403() {
        Blacklist entry = new Blacklist();
        when(blacklistRepository.findActiveByUserId(eq(userId), any()))
                .thenReturn(Optional.of(entry));

        assertThatThrownBy(() -> service.createComment(commentRequest("Hello world"), userId))
                .isInstanceOf(AppException.class)
                .satisfies(e -> {
                    AppException ae = (AppException) e;
                    assertThat(ae.getCode()).isEqualTo("USER_BLACKLISTED");
                    assertThat(ae.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
                });

        verify(commentRepository, never()).save(any());
    }

    // ── Comment rate limit (Q15) ──────────────────────────────────────────────

    @Test
    void createComment_withinRateLimit_succeeds() {
        // 29 comments in the last hour → below the 30/hour limit
        when(commentRepository.countByAuthorUserIdAndCreatedAtAfter(eq(userId), any()))
                .thenReturn(29L);

        CommentResponse r = service.createComment(commentRequest("Great post!"), userId);

        assertThat(r).isNotNull();
    }

    @Test
    void createComment_atRateLimit_throws429WithRetryAfter() {
        // Exactly 30 comments → at the limit (>= 30 triggers rejection)
        when(commentRepository.countByAuthorUserIdAndCreatedAtAfter(eq(userId), any()))
                .thenReturn(30L);

        assertThatThrownBy(() -> service.createComment(commentRequest("One more!"), userId))
                .isInstanceOf(RateLimitException.class)
                .satisfies(e -> {
                    RateLimitException rle = (RateLimitException) e;
                    // Must carry a Retry-After value in seconds (1 hour window)
                    assertThat(rle.getRetryAfterSeconds()).isGreaterThan(0L);
                });

        verify(commentRepository, never()).save(any());
    }

    @Test
    void createComment_aboveRateLimit_throws429() {
        when(commentRepository.countByAuthorUserIdAndCreatedAtAfter(eq(userId), any()))
                .thenReturn(100L);

        assertThatThrownBy(() -> service.createComment(commentRequest("Spamming!"), userId))
                .isInstanceOf(RateLimitException.class);
    }

    // ── Sensitive word → moderation queue (not hard reject) ──────────────────

    @Test
    void createComment_noSensitiveWord_savedAsActive() {
        when(commentRepository.countByAuthorUserIdAndCreatedAtAfter(any(), any())).thenReturn(0L);
        stubSensitiveWords("[\"spam\",\"abuse\"]");

        CommentResponse r = service.createComment(commentRequest("Great research!"), userId);

        assertThat(r.getStatus()).isEqualTo("ACTIVE");
        verify(moderationQueueRepository, never()).save(any());
    }

    @Test
    void createComment_containsSensitiveWord_savedAsPendingAndQueued() {
        when(commentRepository.countByAuthorUserIdAndCreatedAtAfter(any(), any())).thenReturn(0L);
        stubSensitiveWords("[\"spam\",\"abuse\"]");

        CommentResponse r = service.createComment(commentRequest("This is spam content!"), userId);

        // Comment saved but as PENDING, not ACTIVE
        assertThat(r.getStatus()).isEqualTo("PENDING");
        // Moderation queue entry created
        ArgumentCaptor<ModerationQueue> cap = ArgumentCaptor.forClass(ModerationQueue.class);
        verify(moderationQueueRepository).save(cap.capture());
        assertThat(cap.getValue().getQueueType()).isEqualTo("SENSITIVE_WORD");
        assertThat(cap.getValue().getTargetType()).isEqualTo("COMMENT");
    }

    @Test
    void createComment_caseInsensitiveSensitiveWord_detected() {
        when(commentRepository.countByAuthorUserIdAndCreatedAtAfter(any(), any())).thenReturn(0L);
        stubSensitiveWords("[\"abuse\"]");

        // "ABUSE" in upper case should still be detected
        CommentResponse r = service.createComment(commentRequest("This is ABUSE!"), userId);

        assertThat(r.getStatus()).isEqualTo("PENDING");
    }

    @Test
    void createComment_missingOrEmptySensitiveWordConfig_savedAsActive() {
        when(commentRepository.countByAuthorUserIdAndCreatedAtAfter(any(), any())).thenReturn(0L);
        // No sensitive word param configured at all
        when(operationalParamRepository.findById("sensitive_words")).thenReturn(Optional.empty());

        CommentResponse r = service.createComment(commentRequest("spam spam spam"), userId);

        // Without a configured dictionary the comment passes through as ACTIVE
        assertThat(r.getStatus()).isEqualTo("ACTIVE");
    }

    // ── Report rate limit (Q15) ───────────────────────────────────────────────

    @Test
    void createReport_withinRateLimit_succeeds() {
        when(reportRepository.countByReporterUserIdAndCreatedAtAfter(eq(userId), any()))
                .thenReturn(9L); // 9 reports, limit is 10
        when(reportRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        assertThatCode(() -> service.createReport(reportRequest(), userId)).doesNotThrowAnyException();
    }

    @Test
    void createReport_atRateLimit_throws429() {
        when(reportRepository.countByReporterUserIdAndCreatedAtAfter(eq(userId), any()))
                .thenReturn(10L); // at limit (>= 10 triggers rejection)

        assertThatThrownBy(() -> service.createReport(reportRequest(), userId))
                .isInstanceOf(RateLimitException.class)
                .satisfies(e -> {
                    RateLimitException rle = (RateLimitException) e;
                    // 24-hour window → Retry-After ≈ 86400s
                    assertThat(rle.getRetryAfterSeconds()).isEqualTo(86400L);
                });

        verify(reportRepository, never()).save(any());
    }

    // ── Configurable rate limits ──────────────────────────────────────────────

    @Test
    void createComment_customLimit_respected() {
        // Override to a limit of 5
        appProperties.getRateLimit().setCommentsPerHour(5);

        when(commentRepository.countByAuthorUserIdAndCreatedAtAfter(eq(userId), any()))
                .thenReturn(5L); // at limit of 5

        assertThatThrownBy(() -> service.createComment(commentRequest("Comment!"), userId))
                .isInstanceOf(RateLimitException.class);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private CommentCreateRequest commentRequest(String text) {
        CommentCreateRequest r = new CommentCreateRequest();
        r.setContentType("OUTCOME");
        r.setContentId(contentId);
        r.setText(text);
        return r;
    }

    private ReportRequest reportRequest() {
        ReportRequest r = new ReportRequest();
        r.setContentType("COMMENT");
        r.setContentId(contentId);
        r.setReason("Inappropriate content");
        return r;
    }

    private void stubSensitiveWords(String json) {
        OperationalParam p = new OperationalParam();
        p.setKey("sensitive_words");
        p.setValueJson(json);
        when(operationalParamRepository.findById("sensitive_words")).thenReturn(Optional.of(p));
    }
}
