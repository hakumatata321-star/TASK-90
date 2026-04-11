package com.example.ricms.repository;

import com.example.ricms.domain.entity.AuditEvent;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.UUID;

@Repository
public interface AuditEventRepository extends JpaRepository<AuditEvent, UUID> {

    Page<AuditEvent> findByActorUserId(UUID actorUserId, Pageable pageable);

    Page<AuditEvent> findBySubjectResourceType(String subjectResourceType, Pageable pageable);

    Page<AuditEvent> findBySubjectResourceTypeAndSubjectId(
            String subjectResourceType, String subjectId, Pageable pageable);

    /**
     * Flexible filter query for the admin audit log endpoint.
     * All parameters are optional; pass null to skip the corresponding filter.
     */
    @Query("""
            SELECT ae FROM AuditEvent ae
            WHERE (:actorUserId      IS NULL OR ae.actorUserId            = :actorUserId)
              AND (:resourceType     IS NULL OR ae.subjectResourceType     = :resourceType)
              AND (:subjectId        IS NULL OR ae.subjectId               = :subjectId)
              AND (:operation        IS NULL OR ae.operation               = :operation)
              AND (:from             IS NULL OR ae.createdAt              >= :from)
              AND (:to               IS NULL OR ae.createdAt              <= :to)
            ORDER BY ae.createdAt DESC
            """)
    Page<AuditEvent> findWithFilters(
            @Param("actorUserId")  UUID actorUserId,
            @Param("resourceType") String resourceType,
            @Param("subjectId")    String subjectId,
            @Param("operation")    String operation,
            @Param("from")         OffsetDateTime from,
            @Param("to")           OffsetDateTime to,
            Pageable pageable);
}
