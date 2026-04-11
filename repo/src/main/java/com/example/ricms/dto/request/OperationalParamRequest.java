package com.example.ricms.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class OperationalParamRequest {

    @NotBlank
    private String key;

    @NotBlank
    private String valueJson;
}
