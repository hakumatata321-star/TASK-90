package com.example.ricms.dto.response;

import com.example.ricms.domain.enums.OutcomeType;
import lombok.Builder;
import lombok.Data;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Data
@Builder
public class OutcomeResponse {
    private UUID id;
    private OutcomeType type;
    private UUID projectId;
    private String titleOriginal;
    private String status;
    private List<ContributionResponse> contributions;
    private OffsetDateTime createdAt;
}
