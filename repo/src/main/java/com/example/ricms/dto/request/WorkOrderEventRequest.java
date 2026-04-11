package com.example.ricms.dto.request;

import com.example.ricms.domain.enums.WorkOrderEventType;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class WorkOrderEventRequest {

    @NotNull
    private WorkOrderEventType eventType;

    private String payload;
}
