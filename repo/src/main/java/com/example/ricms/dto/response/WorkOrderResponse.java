package com.example.ricms.dto.response;

import com.example.ricms.domain.enums.WorkOrderStatus;
import lombok.Builder;
import lombok.Data;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Data
@Builder
public class WorkOrderResponse {
    private UUID id;
    private String workOrderNumber;
    private UUID orderId;
    private UUID technicianUserId;
    private WorkOrderStatus status;
    private String description;
    private OffsetDateTime slaFirstResponseDueAt;
    private OffsetDateTime slaResolutionDueAt;
    private OffsetDateTime firstRespondedAt;
    private OffsetDateTime resolvedAt;
    private OffsetDateTime createdAt;
    private List<AttachmentResponse> attachments;
}
