package com.example.ricms.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.util.List;
import java.util.UUID;

@Data
public class WorkOrderCreateRequest {
    private UUID orderId;
    @NotBlank
    private String description;
    private List<AttachmentDto> attachments;
}
