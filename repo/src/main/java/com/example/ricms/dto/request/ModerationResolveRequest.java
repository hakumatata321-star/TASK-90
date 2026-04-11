package com.example.ricms.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

@Data
public class ModerationResolveRequest {

    @NotBlank
    @Pattern(regexp = "APPROVE|REJECT", message = "decision must be APPROVE or REJECT")
    private String decision;

    private String reason;
}
