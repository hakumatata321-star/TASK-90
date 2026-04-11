package com.example.ricms.dto.request;

import lombok.Data;

import java.math.BigDecimal;

@Data
public class WorkOrderCostRequest {
    private BigDecimal partsCost;
    private BigDecimal laborCost;
    private String notes;
}
