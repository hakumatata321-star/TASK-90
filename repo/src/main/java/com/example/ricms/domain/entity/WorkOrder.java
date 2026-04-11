package com.example.ricms.domain.entity;

import com.example.ricms.domain.enums.WorkOrderStatus;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "work_orders")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(of = "id")
public class WorkOrder {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    @Column(columnDefinition = "uuid DEFAULT gen_random_uuid()")
    private UUID id;

    @Column(name = "work_order_number", unique = true, nullable = false, length = 50)
    private String workOrderNumber;

    @Column(name = "order_id")
    private UUID orderId;

    @Column(name = "technician_user_id")
    private UUID technicianUserId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private WorkOrderStatus status = WorkOrderStatus.SUBMITTED;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(name = "sla_first_response_due_at", columnDefinition = "TIMESTAMPTZ")
    private OffsetDateTime slaFirstResponseDueAt;

    @Column(name = "sla_resolution_due_at", columnDefinition = "TIMESTAMPTZ")
    private OffsetDateTime slaResolutionDueAt;

    @Column(name = "first_responded_at", columnDefinition = "TIMESTAMPTZ")
    private OffsetDateTime firstRespondedAt;

    @Column(name = "resolved_at", columnDefinition = "TIMESTAMPTZ")
    private OffsetDateTime resolvedAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, columnDefinition = "TIMESTAMPTZ")
    private OffsetDateTime createdAt;
}
