package com.example.ricms.dto.response;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.util.UUID;

@Data
@Builder
public class ContributionResponse {
    private UUID id;
    private UUID contributorUserId;
    private BigDecimal sharePercent;
}
