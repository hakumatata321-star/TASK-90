package com.example.ricms.dto.response;

import lombok.Builder;
import lombok.Data;

import java.time.OffsetDateTime;
import java.util.UUID;

@Data
@Builder
public class CommentResponse {
    private UUID id;
    private UUID authorUserId;
    private String contentType;
    private UUID contentId;
    private String text;
    private String status;
    private OffsetDateTime createdAt;
}
