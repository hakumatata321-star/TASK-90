package com.example.ricms.domain.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "audit_events")
@Immutable
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AuditEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    @Column(columnDefinition = "uuid DEFAULT gen_random_uuid()")
    private UUID id;

    @Column(name = "actor_user_id")
    private UUID actorUserId;

    @Column(name = "subject_resource_type", nullable = false, length = 100)
    private String subjectResourceType;

    @Column(name = "subject_id", length = 255)
    private String subjectId;

    @Column(nullable = false, length = 100)
    private String operation;

    @Column(name = "reason_code", nullable = false, length = 100)
    private String reasonCode;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "diff_payload", columnDefinition = "JSONB")
    private String diffPayload;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, columnDefinition = "TIMESTAMPTZ")
    private OffsetDateTime createdAt;
}
