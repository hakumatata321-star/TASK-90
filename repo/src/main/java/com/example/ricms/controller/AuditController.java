package com.example.ricms.controller;

import com.example.ricms.domain.entity.AuditEvent;
import com.example.ricms.dto.response.AuditEventResponse;
import com.example.ricms.dto.response.PageResponse;
import com.example.ricms.repository.AuditEventRepository;
import com.example.ricms.security.PermissionEnforcer;
import jakarta.persistence.criteria.Predicate;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

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

        Specification<AuditEvent> spec = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (actorUserId != null)  predicates.add(cb.equal(root.get("actorUserId"), actorUserId));
            if (resourceType != null) predicates.add(cb.equal(root.get("subjectResourceType"), resourceType));
            if (subjectId != null)    predicates.add(cb.equal(root.get("subjectId"), subjectId));
            if (operation != null)    predicates.add(cb.equal(root.get("operation"), operation));
            if (from != null)         predicates.add(cb.greaterThanOrEqualTo(root.get("createdAt"), from));
            if (to != null)           predicates.add(cb.lessThanOrEqualTo(root.get("createdAt"), to));
            query.orderBy(cb.desc(root.get("createdAt")));
            return cb.and(predicates.toArray(new Predicate[0]));
        };

        Page<AuditEvent> events = auditEventRepository.findAll(
                spec, PageRequest.of(page, pageSize));

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
