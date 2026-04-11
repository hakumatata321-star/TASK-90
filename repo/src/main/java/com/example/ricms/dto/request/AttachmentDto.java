package com.example.ricms.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class AttachmentDto {
    private String contentType;

    @NotBlank
    private String blobRef;
}
