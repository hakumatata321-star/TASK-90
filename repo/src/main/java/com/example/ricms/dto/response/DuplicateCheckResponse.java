package com.example.ricms.dto.response;

import lombok.Builder;
import lombok.Data;

import java.util.List;
import java.util.UUID;

@Data
@Builder
public class DuplicateCheckResponse {
    private boolean duplicateFound;
    private List<UUID> matchingOutcomeIds;
    private String reason;
}
