package com.example.ricms.dto.response;

import lombok.Builder;
import lombok.Data;

import java.time.OffsetDateTime;
import java.util.UUID;

@Data
@Builder
public class AuditEventResponse {
    private UUID id;
    private UUID actorUserId;
    private String subjectResourceType;
    private String subjectId;
    private String operation;
    private String reasonCode;
    /** Raw JSON diff – only changed fields are present (Q2). */
    private String diffPayload;
    private OffsetDateTime createdAt;
}
