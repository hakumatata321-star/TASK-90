package com.example.ricms.dto.request;

import lombok.Data;

import java.util.List;
import java.util.UUID;

@Data
public class WorkOrderCreateRequest {
    private UUID orderId;
    private String description;
    private List<AttachmentDto> attachments;
}
