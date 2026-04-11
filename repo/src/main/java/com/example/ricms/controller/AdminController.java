package com.example.ricms.controller;

import com.example.ricms.dto.request.ModerationResolveRequest;
import com.example.ricms.dto.request.OperationalParamRequest;
import com.example.ricms.dto.response.KpiResponse;
import com.example.ricms.exception.AppException;
import com.example.ricms.security.SecurityUtils;
import com.example.ricms.service.AdminService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/v1/admin")
@RequiredArgsConstructor
public class AdminController {

    private final AdminService adminService;

    @PostMapping("/moderation-queue/{id}/resolve")
    public ResponseEntity<Void> resolveModeration(
            @PathVariable UUID id,
            @Valid @RequestBody ModerationResolveRequest request) {
        adminService.resolveModeration(id, request.getDecision(), request.getReason(),
                SecurityUtils.getCurrentUserId());
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/kpis")
    public ResponseEntity<KpiResponse> getKpis() {
        return ResponseEntity.ok(adminService.getKpis());
    }

    @PostMapping("/operational-params")
    public ResponseEntity<Void> setOperationalParam(@Valid @RequestBody OperationalParamRequest request) {
        adminService.setOperationalParam(request.getKey(), request.getValueJson(),
                SecurityUtils.getCurrentUserId());
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/coupons/{couponId}/active")
    public ResponseEntity<Void> setCouponActive(
            @PathVariable UUID couponId,
            @RequestParam boolean active) {
        adminService.setCouponActive(couponId, active, SecurityUtils.getCurrentUserId());
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/campaigns/{campaignId}/active")
    public ResponseEntity<Void> setCampaignActive(
            @PathVariable UUID campaignId,
            @RequestParam boolean active) {
        adminService.setCampaignActive(campaignId, active, SecurityUtils.getCurrentUserId());
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/operational-params/{key}")
    public ResponseEntity<String> getOperationalParam(@PathVariable String key) {
        String value = adminService.getOperationalParam(key);
        if (value == null) {
            throw new AppException("PARAM_NOT_FOUND", "Operational parameter not found: " + key, HttpStatus.NOT_FOUND);
        }
        return ResponseEntity.ok(value);
    }

    @GetMapping("/exports/{entity}")
    public ResponseEntity<byte[]> export(
            @PathVariable String entity,
            @RequestParam(defaultValue = "csv") String format) {

        byte[] data;
        String filename;
        String contentType = "csv".equalsIgnoreCase(format)
                ? "text/csv"
                : "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
        String ext = "csv".equalsIgnoreCase(format) ? ".csv" : ".xlsx";

        switch (entity.toLowerCase()) {
            case "orders" -> {
                data = adminService.exportOrders(format);
                filename = "orders" + ext;
            }
            case "work-orders" -> {
                data = adminService.exportWorkOrders(format);
                filename = "work_orders" + ext;
            }
            default -> throw new AppException("UNKNOWN_ENTITY",
                    "Unknown export entity: " + entity + ". Supported: orders, work-orders", HttpStatus.BAD_REQUEST);
        }

        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"");
        return ResponseEntity.ok()
                .headers(headers)
                .contentType(MediaType.parseMediaType(contentType))
                .body(data);
    }
}
