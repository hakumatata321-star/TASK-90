package com.example.ricms.controller;

import com.example.ricms.dto.request.WorkOrderCostRequest;
import com.example.ricms.dto.request.WorkOrderCreateRequest;
import com.example.ricms.dto.request.WorkOrderEventRequest;
import com.example.ricms.dto.request.WorkOrderRatingRequest;
import com.example.ricms.dto.response.WorkOrderAnalyticsResponse;
import com.example.ricms.dto.response.WorkOrderResponse;
import com.example.ricms.security.SecurityUtils;
import com.example.ricms.service.WorkOrderService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.UUID;

@RestController
@RequestMapping("/v1/work-orders")
@RequiredArgsConstructor
public class WorkOrderController {

    private final WorkOrderService workOrderService;

    @PostMapping
    public ResponseEntity<WorkOrderResponse> createWorkOrder(@Valid @RequestBody WorkOrderCreateRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(workOrderService.createWorkOrder(request, SecurityUtils.getCurrentUserId()));
    }

    @GetMapping("/{workOrderId}")
    public ResponseEntity<WorkOrderResponse> getWorkOrder(@PathVariable UUID workOrderId) {
        return ResponseEntity.ok(workOrderService.getWorkOrder(workOrderId));
    }

    @PostMapping("/{workOrderId}/claim")
    public ResponseEntity<WorkOrderResponse> claimWorkOrder(@PathVariable UUID workOrderId) {
        return ResponseEntity.ok(workOrderService.claimWorkOrder(workOrderId, SecurityUtils.getCurrentUserId()));
    }

    @PostMapping("/{workOrderId}/events")
    public ResponseEntity<WorkOrderResponse> addEvent(
            @PathVariable UUID workOrderId,
            @Valid @RequestBody WorkOrderEventRequest request) {
        return ResponseEntity.ok(workOrderService.addEvent(
                workOrderId, request.getEventType(), request.getPayload(), SecurityUtils.getCurrentUserId()));
    }

    @PutMapping("/{workOrderId}/cost")
    public ResponseEntity<WorkOrderResponse> updateCost(
            @PathVariable UUID workOrderId,
            @RequestBody WorkOrderCostRequest request) {
        return ResponseEntity.ok(workOrderService.updateCost(workOrderId, request, SecurityUtils.getCurrentUserId()));
    }

    @PostMapping("/{workOrderId}/rating")
    public ResponseEntity<WorkOrderResponse> submitRating(
            @PathVariable UUID workOrderId,
            @Valid @RequestBody WorkOrderRatingRequest request) {
        return ResponseEntity.ok(workOrderService.submitRating(workOrderId, request, SecurityUtils.getCurrentUserId()));
    }

    @GetMapping("/analytics")
    public ResponseEntity<WorkOrderAnalyticsResponse> getAnalytics(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) UUID technicianId,
            @RequestParam(required = false) String status) {
        return ResponseEntity.ok(workOrderService.getAnalytics(from, to, technicianId, status));
    }

    /**
     * Export work orders as CSV. For Excel export use the admin endpoint
     * {@code GET /v1/admin/exports/work-orders?format=excel}.
     */
    @GetMapping("/export")
    public ResponseEntity<byte[]> exportWorkOrders(
            @RequestParam(defaultValue = "csv") String format) {
        if (!"csv".equalsIgnoreCase(format)) {
            throw new com.example.ricms.exception.AppException("UNSUPPORTED_FORMAT",
                    "This endpoint only supports format=csv. Use /v1/admin/exports/work-orders for Excel.",
                    HttpStatus.BAD_REQUEST);
        }
        byte[] data = workOrderService.exportCsv();
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"work_orders.csv\"");
        return ResponseEntity.ok()
                .headers(headers)
                .contentType(MediaType.parseMediaType("text/csv"))
                .body(data);
    }
}
