package com.example.ricms.dto.response;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;

@Data
@Builder
public class KpiResponse {
    private long totalOrders;
    private BigDecimal totalRevenue;
    private long totalMembers;
    private long activeWorkOrders;
    private long resolvedWorkOrders;
    private long pendingModerationItems;
}
