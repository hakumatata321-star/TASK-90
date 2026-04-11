package com.example.ricms.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.UUID;

@Data
public class ReportRequest {

    @NotBlank
    private String contentType;

    @NotNull
    private UUID contentId;

    @NotBlank
    private String reason;
}
