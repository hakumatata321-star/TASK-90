package com.example.ricms.dto.response;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

@Data
@Builder
public class PointsLedgerResponse {
    private UUID id;
    private Long pointsDelta;
    private UUID sourceOrderId;
    private BigDecimal currencyAmountBasis;
    private String description;
    private OffsetDateTime createdAt;
}
