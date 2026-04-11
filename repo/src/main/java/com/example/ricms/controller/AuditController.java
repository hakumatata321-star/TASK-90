package com.example.ricms.controller;

import com.example.ricms.domain.entity.AuditEvent;
import com.example.ricms.dto.response.AuditEventResponse;
import com.example.ricms.dto.response.PageResponse;
import com.example.ricms.repository.AuditEventRepository;
import com.example.ricms.security.PermissionEnforcer;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * GET /v1/admin/audit-events
 *
 * Queryable admin view of the immutable audit log (Q1/Q2).
 * All parameters are optional – omit to list all events, newest first.
 * Requires ADMIN:READ permission.
 */
@RestController
@RequestMapping("/v1/admin/audit-events")
@RequiredArgsConstructor
public class AuditController {

    private final AuditEventRepository auditEventRepository;
    private final PermissionEnforcer permissionEnforcer;

    @GetMapping
    public ResponseEntity<PageResponse<AuditEventResponse>> listAuditEvents(
            @RequestParam(required = false) UUID actorUserId,
            @RequestParam(required = false) String resourceType,
            @RequestParam(required = false) String subjectId,
            @RequestParam(required = false) String operation,
            @RequestParam(required = false)
                @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime from,
            @RequestParam(required = false)
                @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime to,
            @RequestParam(defaultValue = "0")  int page,
            @RequestParam(defaultValue = "50") int pageSize) {

        permissionEnforcer.require("ADMIN", "READ");

        Page<AuditEvent> events = auditEventRepository.findWithFilters(
                actorUserId, resourceType, subjectId, operation, from, to,
                PageRequest.of(page, pageSize));

        return ResponseEntity.ok(PageResponse.of(events.map(this::toResponse)));
    }

    private AuditEventResponse toResponse(AuditEvent e) {
        return AuditEventResponse.builder()
                .id(e.getId())
                .actorUserId(e.getActorUserId())
                .subjectResourceType(e.getSubjectResourceType())
                .subjectId(e.getSubjectId())
                .operation(e.getOperation())
                .reasonCode(e.getReasonCode())
                .diffPayload(e.getDiffPayload())
                .createdAt(e.getCreatedAt())
                .build();
    }
}
