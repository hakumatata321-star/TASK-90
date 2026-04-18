package com.example.ricms.service;

import com.example.ricms.domain.entity.*;
import com.example.ricms.dto.request.OutcomeCreateRequest;
import com.example.ricms.dto.response.ContributionResponse;
import com.example.ricms.dto.response.DuplicateCheckResponse;
import com.example.ricms.dto.response.OutcomeResponse;
import com.example.ricms.dto.response.PageResponse;
import com.example.ricms.exception.AppException;
import com.example.ricms.repository.*;
import com.example.ricms.security.PermissionEnforcer;
import com.example.ricms.security.SecurityUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class OutcomeService {

    private final OutcomeRepository outcomeRepository;
    private final ContributionRepository contributionRepository;
    private final OutcomeEvidenceRepository outcomeEvidenceRepository;
    private final ModerationQueueRepository moderationQueueRepository;
    private final AuditService auditService;
    private final PermissionEnforcer permissionEnforcer;

    @Transactional
    public OutcomeResponse registerOutcome(OutcomeCreateRequest request, UUID actorId) {
        permissionEnforcer.require("OUTCOME", "WRITE");
        // Validate contributions sum to 100%
        if (request.getContributions() == null || request.getContributions().isEmpty()) {
            throw new AppException("INVALID_CONTRIBUTIONS", "At least one contribution is required", HttpStatus.BAD_REQUEST);
        }
        BigDecimal totalShare = request.getContributions().stream()
                .map(c -> c.getSharePercent() != null ? c.getSharePercent() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (totalShare.compareTo(new BigDecimal("100.00")) != 0) {
            throw new AppException("INVALID_CONTRIBUTIONS",
                    "Contributions must sum to exactly 100%, got: " + totalShare, HttpStatus.BAD_REQUEST);
        }

        // Normalize title
        String titleNormalized = normalizeTitle(request.getTitle());

        // Check certificate uniqueness
        if (request.getCertificateNumber() != null && !request.getCertificateNumber().isBlank()) {
            outcomeRepository.findByCertificateNumber(request.getCertificateNumber())
                    .ifPresent(existing -> {
                        throw new AppException("DUPLICATE_CERTIFICATE",
                                "Certificate number already exists", HttpStatus.CONFLICT);
                    });
        }

        // Check for duplicates
        DuplicateCheckResponse dupCheck = checkDuplicateInternal(titleNormalized, request.getAbstractText());
        String status = dupCheck.isDuplicateFound() ? "UNDER_REVIEW" : "ACTIVE";

        Outcome outcome = Outcome.builder()
                .type(request.getType())
                .projectId(request.getProjectId())
                .titleOriginal(request.getTitle())
                .titleNormalized(titleNormalized)
                .abstractText(request.getAbstractText())
                .certificateNumber(request.getCertificateNumber())
                .status(status)
                .build();
        outcome = outcomeRepository.save(outcome);

        // Save contributions
        for (var contribDto : request.getContributions()) {
            Contribution c = Contribution.builder()
                    .outcomeId(outcome.getId())
                    .contributorUserId(contribDto.getContributorId())
                    .sharePercent(contribDto.getSharePercent())
                    .build();
            contributionRepository.save(c);
        }

        // Save evidences
        if (request.getEvidences() != null) {
            for (var evDto : request.getEvidences()) {
                OutcomeEvidence ev = OutcomeEvidence.builder()
                        .outcomeId(outcome.getId())
                        .evidenceType(evDto.getEvidenceType())
                        .blobRef(evDto.getBlobRef())
                        .build();
                outcomeEvidenceRepository.save(ev);
            }
        }

        // If duplicate suspected, add to moderation queue
        if (dupCheck.isDuplicateFound()) {
            ModerationQueue mq = ModerationQueue.builder()
                    .queueType("DUPLICATE_REVIEW")
                    .targetType("OUTCOME")
                    .targetId(outcome.getId())
                    .payload("{\"reason\":\"" + dupCheck.getReason() + "\"}")
                    .status("PENDING")
                    .build();
            moderationQueueRepository.save(mq);
        }

        auditService.record(actorId, "OUTCOME", outcome.getId().toString(),
                "REGISTER", "USER_ACTION", null, outcome.getTitleOriginal());

        return toResponse(outcome);
    }

    public DuplicateCheckResponse checkDuplicate(OutcomeCreateRequest request) {
        permissionEnforcer.require("OUTCOME", "READ");
        String normalized = normalizeTitle(request.getTitle());
        return checkDuplicateInternal(normalized, request.getAbstractText());
    }

    private DuplicateCheckResponse checkDuplicateInternal(String titleNormalized, String abstractText) {
        List<Outcome> exactMatches = outcomeRepository.findByTitleNormalized(titleNormalized);
        if (!exactMatches.isEmpty()) {
            return DuplicateCheckResponse.builder()
                    .duplicateFound(true)
                    .matchingOutcomeIds(exactMatches.stream().map(Outcome::getId).collect(Collectors.toList()))
                    .reason("Exact title match found")
                    .build();
        }

        // Check abstract token overlap using pre-filtered query (avoids loading all outcomes)
        if (abstractText != null && !abstractText.isBlank()) {
            List<Outcome> candidates = outcomeRepository.findWithAbstractText();
            List<UUID> abstractMatches = new ArrayList<>();
            for (Outcome existing : candidates) {
                double similarity = tokenOverlap(abstractText, existing.getAbstractText());
                if (similarity >= 0.70) {
                    abstractMatches.add(existing.getId());
                }
            }
            if (!abstractMatches.isEmpty()) {
                return DuplicateCheckResponse.builder()
                        .duplicateFound(true)
                        .matchingOutcomeIds(abstractMatches)
                        .reason("High abstract similarity detected (>= 70%)")
                        .build();
            }
        }

        return DuplicateCheckResponse.builder()
                .duplicateFound(false)
                .matchingOutcomeIds(List.of())
                .reason(null)
                .build();
    }

    public double tokenOverlap(String a, String b) {
        Set<String> tokensA = tokenize(a);
        Set<String> tokensB = tokenize(b);
        if (tokensA.isEmpty() || tokensB.isEmpty()) return 0.0;

        Set<String> intersection = new HashSet<>(tokensA);
        intersection.retainAll(tokensB);

        Set<String> union = new HashSet<>(tokensA);
        union.addAll(tokensB);

        return (double) intersection.size() / union.size();
    }

    private Set<String> tokenize(String text) {
        if (text == null) return Set.of();
        return Arrays.stream(text.toLowerCase().split("[\\s\\p{Punct}]+"))
                .filter(t -> !t.isBlank() && t.length() > 2)
                .collect(Collectors.toSet());
    }

    public String normalizeTitle(String title) {
        if (title == null) return "";
        return title.toLowerCase()
                .replaceAll("[^a-z0-9\\s]", "")
                .trim()
                .replaceAll("\\s+", " ");
    }

    public List<ContributionResponse> listContributions(UUID outcomeId) {
        permissionEnforcer.require("OUTCOME", "READ");
        outcomeRepository.findById(outcomeId)
                .orElseThrow(() -> new AppException("OUTCOME_NOT_FOUND", "Outcome not found", HttpStatus.NOT_FOUND));
        // RLS: non-admin callers only see their own contribution row
        List<Contribution> contribs;
        if (SecurityUtils.hasPermission("ADMIN", "READ")) {
            contribs = contributionRepository.findByOutcomeId(outcomeId);
        } else {
            UUID callerId = SecurityUtils.getCurrentUserId();
            contribs = contributionRepository.findByOutcomeIdAndContributorUserId(outcomeId, callerId);
        }
        return contribs.stream()
                .map(c -> ContributionResponse.builder()
                        .id(c.getId())
                        .contributorUserId(c.getContributorUserId())
                        .sharePercent(c.getSharePercent())
                        .build())
                .collect(Collectors.toList());
    }

    public PageResponse<OutcomeResponse> listByProject(UUID projectId, int page, int pageSize) {
        permissionEnforcer.require("OUTCOME", "READ");
        var pageable = PageRequest.of(page, pageSize);
        return PageResponse.of(outcomeRepository.findByProjectId(projectId, pageable).map(this::toResponse));
    }

    public OutcomeResponse getOutcome(UUID outcomeId) {
        permissionEnforcer.require("OUTCOME", "READ");
        Outcome outcome = outcomeRepository.findById(outcomeId)
                .orElseThrow(() -> new AppException("OUTCOME_NOT_FOUND", "Outcome not found", HttpStatus.NOT_FOUND));
        return toResponse(outcome);
    }

    public OutcomeResponse toResponse(Outcome outcome) {
        List<ContributionResponse> contribs = contributionRepository.findByOutcomeId(outcome.getId())
                .stream().map(c -> ContributionResponse.builder()
                        .id(c.getId())
                        .contributorUserId(c.getContributorUserId())
                        .sharePercent(c.getSharePercent())
                        .build())
                .collect(Collectors.toList());

        return OutcomeResponse.builder()
                .id(outcome.getId())
                .type(outcome.getType())
                .projectId(outcome.getProjectId())
                .titleOriginal(outcome.getTitleOriginal())
                .status(outcome.getStatus())
                .contributions(contribs)
                .createdAt(outcome.getCreatedAt())
                .build();
    }
}
