package com.example.ricms.dto.request;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;
import java.util.UUID;

@Data
public class ContributionDto {

    @NotNull
    private UUID contributorId;

    @NotNull
    private BigDecimal sharePercent;
}
