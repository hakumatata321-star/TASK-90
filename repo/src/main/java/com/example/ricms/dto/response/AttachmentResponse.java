package com.example.ricms.dto.response;

import lombok.Builder;
import lombok.Data;

import java.time.OffsetDateTime;
import java.util.UUID;

@Data
@Builder
public class AttachmentResponse {
    private UUID   id;
    private String ownerType;
    private UUID   ownerId;
    private String blobRef;
    private String contentType;
    private OffsetDateTime createdAt;
}
