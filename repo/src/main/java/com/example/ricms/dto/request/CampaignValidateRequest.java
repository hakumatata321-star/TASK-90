package com.example.ricms.dto.request;

import lombok.Data;

import java.util.UUID;

@Data
public class CampaignValidateRequest {
    private UUID campaignId;
    private OrderContextDto orderContext;
}
