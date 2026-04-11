package com.example.ricms.controller;

import com.example.ricms.dto.request.CommentCreateRequest;
import com.example.ricms.dto.request.FavoriteRequest;
import com.example.ricms.dto.request.LikeRequest;
import com.example.ricms.dto.request.ReportRequest;
import com.example.ricms.dto.response.CommentResponse;
import com.example.ricms.dto.response.PageResponse;
import com.example.ricms.security.SecurityUtils;
import com.example.ricms.service.InteractionService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/v1/interactions")
@RequiredArgsConstructor
public class InteractionController {

    private final InteractionService interactionService;

    @PostMapping("/comments")
    public ResponseEntity<CommentResponse> createComment(@Valid @RequestBody CommentCreateRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(interactionService.createComment(request, SecurityUtils.getCurrentUserId()));
    }

    @GetMapping("/comments")
    public ResponseEntity<PageResponse<CommentResponse>> getComments(
            @RequestParam String contentType,
            @RequestParam UUID contentId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        return ResponseEntity.ok(interactionService.getComments(contentType, contentId, page, pageSize));
    }

    @PostMapping("/likes")
    public ResponseEntity<Void> createLike(@Valid @RequestBody LikeRequest request) {
        interactionService.createLike(request, SecurityUtils.getCurrentUserId());
        return ResponseEntity.status(HttpStatus.CREATED).build();
    }

    @PostMapping("/favorites")
    public ResponseEntity<Void> createFavorite(@Valid @RequestBody FavoriteRequest request) {
        interactionService.createFavorite(request, SecurityUtils.getCurrentUserId());
        return ResponseEntity.status(HttpStatus.CREATED).build();
    }

    @PostMapping("/reports")
    public ResponseEntity<Void> createReport(@Valid @RequestBody ReportRequest request) {
        interactionService.createReport(request, SecurityUtils.getCurrentUserId());
        return ResponseEntity.status(HttpStatus.CREATED).build();
    }
}
