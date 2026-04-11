package com.example.ricms.dto.response;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;

@Data
@Builder
public class WorkOrderAnalyticsResponse {
    private long total;
    private long submitted;
    private long assigned;
    private long inProgress;
    private long resolved;
    private long closed;
    private double avgRating;
    private BigDecimal totalPartsCost;
    private BigDecimal totalLaborCost;
}
