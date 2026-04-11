package com.example.ricms.controller;

import com.example.ricms.dto.request.OutcomeCreateRequest;
import com.example.ricms.dto.response.ContributionResponse;
import com.example.ricms.dto.response.DuplicateCheckResponse;
import com.example.ricms.dto.response.OutcomeResponse;
import com.example.ricms.dto.response.PageResponse;
import com.example.ricms.security.SecurityUtils;
import com.example.ricms.service.OutcomeService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequiredArgsConstructor
public class OutcomeController {

    private final OutcomeService outcomeService;

    @PostMapping("/v1/outcomes")
    public ResponseEntity<OutcomeResponse> registerOutcome(@Valid @RequestBody OutcomeCreateRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(outcomeService.registerOutcome(request, SecurityUtils.getCurrentUserId()));
    }

    @GetMapping("/v1/outcomes/{outcomeId}")
    public ResponseEntity<OutcomeResponse> getOutcome(@PathVariable UUID outcomeId) {
        return ResponseEntity.ok(outcomeService.getOutcome(outcomeId));
    }

    @PostMapping("/v1/outcomes/duplicates/check")
    public ResponseEntity<DuplicateCheckResponse> checkDuplicate(@RequestBody OutcomeCreateRequest request) {
        return ResponseEntity.ok(outcomeService.checkDuplicate(request));
    }

    @GetMapping("/v1/outcomes/{outcomeId}/contributions")
    public ResponseEntity<List<ContributionResponse>> listContributions(@PathVariable UUID outcomeId) {
        return ResponseEntity.ok(outcomeService.listContributions(outcomeId));
    }

    @GetMapping("/v1/projects/{projectId}/outcomes")
    public ResponseEntity<PageResponse<OutcomeResponse>> listByProject(
            @PathVariable UUID projectId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        return ResponseEntity.ok(outcomeService.listByProject(projectId, page, pageSize));
    }
}
