package com.example.ricms.service;

import com.example.ricms.domain.entity.AuditEvent;
import com.example.ricms.repository.AuditEventRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/**
 * Append-only audit trail (Q1 / Q2).
 *
 * Diff storage (Q2): rather than persisting full before/after snapshots, we
 * compute a field-level diff and store only the changed keys:
 *
 *   { "roles": {"from": ["MEMBER"], "to": ["MEMBER","ADMIN"]},
 *     "status": {"from": "ACTIVE", "to": "SUSPENDED"} }
 *
 * This keeps each row small and makes the change obvious on inspection.
 * The table is append-only (@Immutable entity, no UPDATE issued by the ORM).
 *
 * Archival (Q2): use Propagation.REQUIRES_NEW so audit writes survive even if
 * the outer transaction rolls back. For cold-storage archival, a periodic job
 * can move rows older than N months into an audit_events_archive table using
 * the existing (resource_type, created_at) index; no code change needed.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuditService {

    private final AuditEventRepository auditEventRepository;
    private final ObjectMapper objectMapper;

    /**
     * Record a privileged action with a diff of changed fields.
     *
     * @param actorId      user who performed the action (null for system actions)
     * @param resourceType domain entity type, e.g. "USER", "ORDER"
     * @param subjectId    identifier of the affected resource
     * @param operation    verb, e.g. "ASSIGN_ROLES", "PASSWORD_ROTATE"
     * @param reasonCode   structured reason, e.g. "ADMIN_ACTION", "USER_REQUEST"
     * @param before       previous state (POJO, Map, or null if creating)
     * @param after        new state (POJO, Map, or null if deleting)
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(UUID actorId, String resourceType, String subjectId,
                       String operation, String reasonCode, Object before, Object after) {
        try {
            String diffJson = computeDiff(before, after);
            AuditEvent event = AuditEvent.builder()
                    .actorUserId(actorId)
                    .subjectResourceType(resourceType)
                    .subjectId(subjectId)
                    .operation(operation)
                    .reasonCode(reasonCode)
                    .diffPayload(diffJson)
                    .build();
            auditEventRepository.save(event);
        } catch (Exception e) {
            // Audit failure must never break the primary transaction
            log.error("Failed to record audit event [{}:{}:{}]: {}", resourceType, operation, subjectId, e.getMessage());
        }
    }

    // -------------------------------------------------------------------------
    // Diff computation (Q2)
    // -------------------------------------------------------------------------

    /**
     * Compute a field-level diff between two states.
     *
     * Rules:
     * - If both are null → no diff stored (returns null).
     * - If before is null → treat as creation, store {"_action":"CREATED","after":{...}}.
     * - If after  is null → treat as deletion, store {"_action":"DELETED","before":{...}}.
     * - Otherwise → iterate all keys from both maps; emit only keys where
     *   the value changed: {"fieldName":{"from":old,"to":new}}.
     * - Password / token fields are masked as "[REDACTED]" before serialisation.
     */
    private String computeDiff(Object before, Object after) throws Exception {
        if (before == null && after == null) {
            return null;
        }

        if (before == null) {
            Map<String, Object> afterMap = toMap(after);
            maskSensitiveFields(afterMap);
            return objectMapper.writeValueAsString(
                    Map.of("_action", "CREATED", "after", afterMap));
        }

        if (after == null) {
            Map<String, Object> beforeMap = toMap(before);
            maskSensitiveFields(beforeMap);
            return objectMapper.writeValueAsString(
                    Map.of("_action", "DELETED", "before", beforeMap));
        }

        Map<String, Object> beforeMap = toMap(before);
        Map<String, Object> afterMap  = toMap(after);
        maskSensitiveFields(beforeMap);
        maskSensitiveFields(afterMap);

        Map<String, Object> diff = new LinkedHashMap<>();
        Set<String> allKeys = new LinkedHashSet<>();
        allKeys.addAll(beforeMap.keySet());
        allKeys.addAll(afterMap.keySet());

        for (String key : allKeys) {
            Object bVal = beforeMap.get(key);
            Object aVal = afterMap.get(key);
            if (!Objects.equals(bVal, aVal)) {
                Map<String, Object> change = new LinkedHashMap<>();
                change.put("from", bVal != null ? bVal : "<null>");
                change.put("to",   aVal != null ? aVal : "<null>");
                diff.put(key, change);
            }
        }

        if (diff.isEmpty()) {
            return null; // nothing actually changed
        }
        return objectMapper.writeValueAsString(diff);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> toMap(Object obj) {
        if (obj instanceof Map) {
            return new LinkedHashMap<>((Map<String, Object>) obj);
        }
        // POJO → Map via Jackson
        return objectMapper.convertValue(obj, new TypeReference<Map<String, Object>>() {});
    }

    private static final Set<String> SENSITIVE_FIELDS = Set.of(
            "passwordHash", "password", "token", "accessToken", "secret");

    private void maskSensitiveFields(Map<String, Object> map) {
        for (String field : SENSITIVE_FIELDS) {
            if (map.containsKey(field)) {
                map.put(field, "[REDACTED]");
            }
        }
    }
}
