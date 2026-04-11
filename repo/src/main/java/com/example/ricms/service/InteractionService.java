package com.example.ricms.service;

import com.example.ricms.config.AppProperties;
import com.example.ricms.domain.entity.*;
import com.example.ricms.dto.request.CommentCreateRequest;
import com.example.ricms.dto.request.FavoriteRequest;
import com.example.ricms.dto.request.LikeRequest;
import com.example.ricms.dto.request.ReportRequest;
import com.example.ricms.dto.response.CommentResponse;
import com.example.ricms.dto.response.PageResponse;
import com.example.ricms.exception.AppException;
import com.example.ricms.exception.RateLimitException;
import com.example.ricms.repository.*;
import com.example.ricms.security.PermissionEnforcer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class InteractionService {

    private final CommentRepository commentRepository;
    private final LikeRepository likeRepository;
    private final FavoriteRepository favoriteRepository;
    private final ReportRepository reportRepository;
    private final ModerationQueueRepository moderationQueueRepository;
    private final BlacklistRepository blacklistRepository;
    private final OperationalParamRepository operationalParamRepository;
    private final AppProperties appProperties;
    private final PermissionEnforcer permissionEnforcer;

    @Transactional
    public CommentResponse createComment(CommentCreateRequest request, UUID userId) {
        permissionEnforcer.require("INTERACTION", "WRITE");
        // Check blacklist
        Optional<Blacklist> blacklisted = blacklistRepository.findActiveByUserId(userId, OffsetDateTime.now());
        if (blacklisted.isPresent()) {
            throw new AppException("USER_BLACKLISTED", "User is blacklisted and cannot post comments", HttpStatus.FORBIDDEN);
        }

        // Rate limit: 30 comments per hour
        OffsetDateTime oneHourAgo = OffsetDateTime.now().minusHours(1);
        long recentComments = commentRepository.countByAuthorUserIdAndCreatedAtAfter(userId, oneHourAgo);
        int limit = appProperties.getRateLimit().getCommentsPerHour();
        if (recentComments >= limit) {
            throw new RateLimitException("Comment rate limit exceeded: " + limit + " per hour", 3600L);
        }

        // Sensitive-word check: flag for review rather than hard-reject so that
        // moderators can decide. Comment is saved with PENDING status and queued.
        boolean hasSensitiveWord = containsSensitiveWord(request.getText());
        String commentStatus = hasSensitiveWord ? "PENDING" : "ACTIVE";

        Comment comment = Comment.builder()
                .authorUserId(userId)
                .contentType(request.getContentType())
                .contentId(request.getContentId())
                .text(request.getText())
                .status(commentStatus)
                .build();
        comment = commentRepository.save(comment);

        if (hasSensitiveWord) {
            ModerationQueue mq = ModerationQueue.builder()
                    .queueType("SENSITIVE_WORD")
                    .targetType("COMMENT")
                    .targetId(comment.getId())
                    .payload("{\"reason\":\"Sensitive word detected\"}")
                    .status("PENDING")
                    .build();
            moderationQueueRepository.save(mq);
        }

        return toCommentResponse(comment);
    }

    @Transactional
    public void createLike(LikeRequest request, UUID userId) {
        permissionEnforcer.require("INTERACTION", "WRITE");
        // Upsert: ignore if already liked
        Optional<Like> existing = likeRepository.findByUserIdAndContentTypeAndContentId(
                userId, request.getContentType(), request.getContentId());
        if (existing.isEmpty()) {
            Like like = Like.builder()
                    .userId(userId)
                    .contentType(request.getContentType())
                    .contentId(request.getContentId())
                    .build();
            likeRepository.save(like);
        }
    }

    @Transactional
    public void createFavorite(FavoriteRequest request, UUID userId) {
        permissionEnforcer.require("INTERACTION", "WRITE");
        Optional<Favorite> existing = favoriteRepository.findByUserIdAndContentTypeAndContentId(
                userId, request.getContentType(), request.getContentId());
        if (existing.isEmpty()) {
            Favorite favorite = Favorite.builder()
                    .userId(userId)
                    .contentType(request.getContentType())
                    .contentId(request.getContentId())
                    .build();
            favoriteRepository.save(favorite);
        }
    }

    @Transactional
    public void createReport(ReportRequest request, UUID userId) {
        permissionEnforcer.require("INTERACTION", "WRITE");
        // Rate limit: 10 reports per day
        OffsetDateTime oneDayAgo = OffsetDateTime.now().minusDays(1);
        long recentReports = reportRepository.countByReporterUserIdAndCreatedAtAfter(userId, oneDayAgo);
        int limit = appProperties.getRateLimit().getReportsPerDay();
        if (recentReports >= limit) {
            throw new RateLimitException("Report rate limit exceeded: " + limit + " per day", 86400L);
        }

        Report report = Report.builder()
                .reporterUserId(userId)
                .contentType(request.getContentType())
                .contentId(request.getContentId())
                .reason(request.getReason())
                .status("PENDING")
                .build();
        reportRepository.save(report);

        // Add to moderation queue
        ModerationQueue mq = ModerationQueue.builder()
                .queueType("REPORT")
                .targetType(request.getContentType())
                .targetId(request.getContentId())
                .payload("{\"reason\":\"" + request.getReason().replace("\"", "\\\"") + "\"}")
                .status("PENDING")
                .build();
        moderationQueueRepository.save(mq);
    }

    public PageResponse<CommentResponse> getComments(String contentType, UUID contentId, int page, int pageSize) {
        var commentPage = commentRepository.findByContentTypeAndContentIdOrderByCreatedAtDesc(
                contentType, contentId, PageRequest.of(page, pageSize));
        return PageResponse.of(commentPage.map(this::toCommentResponse));
    }

    /**
     * Returns true if the text contains any word from the sensitive_words
     * operational param.  Never throws — on parse error returns false so that
     * a misconfigured param does not block all comments.
     */
    private boolean containsSensitiveWord(String text) {
        try {
            return operationalParamRepository.findById("sensitive_words")
                    .map(param -> {
                        String valueJson = param.getValueJson();
                        if (valueJson == null || !valueJson.startsWith("[")) return false;
                        String cleaned = valueJson.replaceAll("[\\[\\]\"\\s]", "");
                        String textLower = text.toLowerCase();
                        for (String word : cleaned.split(",")) {
                            if (!word.isBlank() && textLower.contains(word.toLowerCase())) {
                                return true;
                            }
                        }
                        return false;
                    })
                    .orElse(false);
        } catch (Exception e) {
            log.warn("Failed to check sensitive words: {}", e.getMessage());
            return false;
        }
    }

    private CommentResponse toCommentResponse(Comment c) {
        return CommentResponse.builder()
                .id(c.getId())
                .authorUserId(c.getAuthorUserId())
                .contentType(c.getContentType())
                .contentId(c.getContentId())
                .text(c.getText())
                .status(c.getStatus())
                .createdAt(c.getCreatedAt())
                .build();
    }
}
