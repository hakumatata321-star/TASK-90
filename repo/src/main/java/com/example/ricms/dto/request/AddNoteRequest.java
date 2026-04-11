package com.example.ricms.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class AddNoteRequest {

    @NotBlank
    private String note;
}
