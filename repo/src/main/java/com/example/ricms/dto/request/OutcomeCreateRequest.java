package com.example.ricms.dto.request;

import com.example.ricms.domain.enums.OutcomeType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;
import java.util.UUID;

@Data
public class OutcomeCreateRequest {

    @NotNull
    private OutcomeType type;

    @NotBlank
    private String title;

    private String abstractText;
    private String certificateNumber;
    private UUID projectId;

    @Valid
    private List<EvidenceDto> evidences;

    @Valid
    @NotNull
    private List<ContributionDto> contributions;
}
