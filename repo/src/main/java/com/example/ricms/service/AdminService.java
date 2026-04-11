package com.example.ricms.service;

import com.example.ricms.domain.entity.*;
import com.example.ricms.domain.enums.OrderStatus;
import com.example.ricms.domain.enums.WorkOrderStatus;
import com.example.ricms.dto.response.KpiResponse;
import com.example.ricms.exception.AppException;
import com.example.ricms.repository.*;
import com.example.ricms.security.PermissionEnforcer;
import java.util.Map;
import java.util.UUID;
import com.opencsv.CSVWriter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayOutputStream;
import java.io.StringWriter;
import java.math.BigDecimal;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class AdminService {

    private final OrderRepository orderRepository;
    private final WorkOrderRepository workOrderRepository;
    private final MemberRepository memberRepository;
    private final ModerationQueueRepository moderationQueueRepository;
    private final OperationalParamRepository operationalParamRepository;
    private final OrderItemRepository orderItemRepository;
    private final CouponRepository couponRepository;
    private final CampaignRepository campaignRepository;
    private final OutcomeRepository outcomeRepository;
    private final PermissionEnforcer permissionEnforcer;
    private final AuditService auditService;

    public KpiResponse getKpis() {
        permissionEnforcer.require("ADMIN", "READ");
        long totalOrders = orderRepository.count();
        BigDecimal totalRevenue = orderRepository.sumCompletedRevenue();
        long totalMembers = memberRepository.count();
        long activeWorkOrders = workOrderRepository.countByStatus(WorkOrderStatus.IN_PROGRESS)
                + workOrderRepository.countByStatus(WorkOrderStatus.ASSIGNED)
                + workOrderRepository.countByStatus(WorkOrderStatus.SUBMITTED);
        long resolvedWorkOrders = workOrderRepository.countByStatus(WorkOrderStatus.RESOLVED)
                + workOrderRepository.countByStatus(WorkOrderStatus.CLOSED);
        long pendingModerationItems = moderationQueueRepository.countByStatus("PENDING");

        return KpiResponse.builder()
                .totalOrders(totalOrders)
                .totalRevenue(totalRevenue != null ? totalRevenue : BigDecimal.ZERO)
                .totalMembers(totalMembers)
                .activeWorkOrders(activeWorkOrders)
                .resolvedWorkOrders(resolvedWorkOrders)
                .pendingModerationItems(pendingModerationItems)
                .build();
    }

    @Transactional
    public void setOperationalParam(String key, String valueJson, UUID actorId) {
        permissionEnforcer.require("ADMIN", "WRITE");
        OperationalParam param = operationalParamRepository.findById(key)
                .orElseGet(() -> OperationalParam.builder().key(key).build());
        String previousValue = param.getValueJson();
        param.setValueJson(valueJson);
        operationalParamRepository.save(param);
        auditService.record(actorId, "OPERATIONAL_PARAM", key,
                "SET_PARAM", "ADMIN_ACTION",
                Map.of("value", previousValue != null ? previousValue : "<null>"),
                Map.of("value", valueJson));
    }

    @Transactional
    public void setCouponActive(UUID couponId, boolean active, UUID actorId) {
        permissionEnforcer.require("ADMIN", "WRITE");
        Coupon coupon = couponRepository.findById(couponId)
                .orElseThrow(() -> new AppException("NOT_FOUND", "Coupon not found: " + couponId, HttpStatus.NOT_FOUND));
        boolean previous = coupon.isActive();
        coupon.setActive(active);
        couponRepository.save(coupon);
        auditService.record(actorId, "COUPON", couponId.toString(),
                "SET_ACTIVE", "ADMIN_ACTION",
                Map.of("isActive", previous),
                Map.of("isActive", active));
    }

    @Transactional
    public void setCampaignActive(UUID campaignId, boolean active, UUID actorId) {
        permissionEnforcer.require("ADMIN", "WRITE");
        Campaign campaign = campaignRepository.findById(campaignId)
                .orElseThrow(() -> new AppException("NOT_FOUND", "Campaign not found: " + campaignId, HttpStatus.NOT_FOUND));
        boolean previous = campaign.isActive();
        campaign.setActive(active);
        campaignRepository.save(campaign);
        auditService.record(actorId, "CAMPAIGN", campaignId.toString(),
                "SET_ACTIVE", "ADMIN_ACTION",
                Map.of("isActive", previous),
                Map.of("isActive", active));
    }

    @Transactional
    public void resolveModeration(UUID queueItemId, String decision, String reason, UUID actorId) {
        permissionEnforcer.require("ADMIN", "WRITE");

        ModerationQueue item = moderationQueueRepository.findById(queueItemId)
                .orElseThrow(() -> new AppException("NOT_FOUND",
                        "Moderation queue item not found: " + queueItemId, HttpStatus.NOT_FOUND));

        if (!"PENDING".equals(item.getStatus())) {
            throw new AppException("ALREADY_RESOLVED",
                    "Moderation item has already been resolved with status: " + item.getStatus(),
                    HttpStatus.CONFLICT);
        }

        String newStatus = "APPROVE".equalsIgnoreCase(decision) ? "APPROVED" : "REJECTED";
        item.setStatus(newStatus);
        moderationQueueRepository.save(item);

        // Propagate decision to the target entity
        if ("OUTCOME".equals(item.getTargetType())) {
            outcomeRepository.findById(item.getTargetId()).ifPresent(outcome -> {
                outcome.setStatus("APPROVE".equalsIgnoreCase(decision) ? "ACTIVE" : "REJECTED");
                outcomeRepository.save(outcome);
            });
        }

        auditService.record(actorId, "MODERATION_QUEUE", queueItemId.toString(),
                "RESOLVE_MODERATION", "ADMIN_MODERATION",
                Map.of("status", "PENDING", "targetType", item.getTargetType(),
                        "targetId", item.getTargetId().toString()),
                Map.of("status", newStatus, "decision", decision,
                        "reason", reason != null ? reason : ""));
    }

    public String getOperationalParam(String key) {
        permissionEnforcer.require("ADMIN", "READ");
        return operationalParamRepository.findById(key)
                .map(OperationalParam::getValueJson)
                .orElse(null);
    }

    public byte[] exportOrders(String format) {
        permissionEnforcer.require("ADMIN", "READ");
        List<Order> orders = orderRepository.findAll();
        if ("excel".equalsIgnoreCase(format)) {
            return exportOrdersExcel(orders);
        } else {
            return exportOrdersCsv(orders);
        }
    }

    public byte[] exportWorkOrders(String format) {
        permissionEnforcer.require("ADMIN", "READ");
        List<WorkOrder> workOrders = workOrderRepository.findAll();
        if ("excel".equalsIgnoreCase(format)) {
            return exportWorkOrdersExcel(workOrders);
        } else {
            return exportWorkOrdersCsv(workOrders);
        }
    }

    private byte[] exportOrdersCsv(List<Order> orders) {
        try (StringWriter sw = new StringWriter(); CSVWriter writer = new CSVWriter(sw)) {
            writer.writeNext(new String[]{
                    "ID", "Order Number", "Buyer ID", "Status",
                    "Subtotal", "Discounts", "Shipping", "Total",
                    "Payment Method", "Created At"
            });
            for (Order o : orders) {
                writer.writeNext(new String[]{
                        o.getId().toString(),
                        o.getOrderNumber(),
                        o.getBuyerUserId() != null ? o.getBuyerUserId().toString() : "",
                        o.getStatus().name(),
                        o.getSubtotal().toPlainString(),
                        o.getDiscountsTotal().toPlainString(),
                        o.getShippingTotal().toPlainString(),
                        o.getTotalPayable().toPlainString(),
                        o.getPaymentMethod().name(),
                        o.getCreatedAt().toString()
                });
            }
            return sw.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new AppException("EXPORT_FAILED", "Failed to export orders CSV", HttpStatus.INTERNAL_SERVER_ERROR, e);
        }
    }

    private byte[] exportOrdersExcel(List<Order> orders) {
        try (Workbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = wb.createSheet("Orders");
            String[] headers = {"ID", "Order Number", "Buyer ID", "Status",
                    "Subtotal", "Discounts", "Shipping", "Total", "Payment Method", "Created At"};
            Row headerRow = sheet.createRow(0);
            for (int i = 0; i < headers.length; i++) {
                headerRow.createCell(i).setCellValue(headers[i]);
            }
            int rowNum = 1;
            for (Order o : orders) {
                Row row = sheet.createRow(rowNum++);
                row.createCell(0).setCellValue(o.getId().toString());
                row.createCell(1).setCellValue(o.getOrderNumber());
                row.createCell(2).setCellValue(o.getBuyerUserId() != null ? o.getBuyerUserId().toString() : "");
                row.createCell(3).setCellValue(o.getStatus().name());
                row.createCell(4).setCellValue(o.getSubtotal().doubleValue());
                row.createCell(5).setCellValue(o.getDiscountsTotal().doubleValue());
                row.createCell(6).setCellValue(o.getShippingTotal().doubleValue());
                row.createCell(7).setCellValue(o.getTotalPayable().doubleValue());
                row.createCell(8).setCellValue(o.getPaymentMethod().name());
                row.createCell(9).setCellValue(o.getCreatedAt().toString());
            }
            wb.write(out);
            return out.toByteArray();
        } catch (Exception e) {
            throw new AppException("EXPORT_FAILED", "Failed to export orders Excel", HttpStatus.INTERNAL_SERVER_ERROR, e);
        }
    }

    private byte[] exportWorkOrdersCsv(List<WorkOrder> workOrders) {
        try (StringWriter sw = new StringWriter(); CSVWriter writer = new CSVWriter(sw)) {
            writer.writeNext(new String[]{
                    "ID", "Number", "Order ID", "Technician ID", "Status", "Description", "Created At"
            });
            for (WorkOrder wo : workOrders) {
                writer.writeNext(new String[]{
                        wo.getId().toString(),
                        wo.getWorkOrderNumber(),
                        wo.getOrderId() != null ? wo.getOrderId().toString() : "",
                        wo.getTechnicianUserId() != null ? wo.getTechnicianUserId().toString() : "",
                        wo.getStatus().name(),
                        wo.getDescription() != null ? wo.getDescription() : "",
                        wo.getCreatedAt().toString()
                });
            }
            return sw.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new AppException("EXPORT_FAILED", "Failed to export work orders CSV", HttpStatus.INTERNAL_SERVER_ERROR, e);
        }
    }

    private byte[] exportWorkOrdersExcel(List<WorkOrder> workOrders) {
        try (Workbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = wb.createSheet("Work Orders");
            String[] headers = {"ID", "Number", "Order ID", "Technician ID", "Status", "Description", "Created At"};
            Row headerRow = sheet.createRow(0);
            for (int i = 0; i < headers.length; i++) {
                headerRow.createCell(i).setCellValue(headers[i]);
            }
            int rowNum = 1;
            for (WorkOrder wo : workOrders) {
                Row row = sheet.createRow(rowNum++);
                row.createCell(0).setCellValue(wo.getId().toString());
                row.createCell(1).setCellValue(wo.getWorkOrderNumber());
                row.createCell(2).setCellValue(wo.getOrderId() != null ? wo.getOrderId().toString() : "");
                row.createCell(3).setCellValue(wo.getTechnicianUserId() != null ? wo.getTechnicianUserId().toString() : "");
                row.createCell(4).setCellValue(wo.getStatus().name());
                row.createCell(5).setCellValue(wo.getDescription() != null ? wo.getDescription() : "");
                row.createCell(6).setCellValue(wo.getCreatedAt().toString());
            }
            wb.write(out);
            return out.toByteArray();
        } catch (Exception e) {
            throw new AppException("EXPORT_FAILED", "Failed to export work orders Excel", HttpStatus.INTERNAL_SERVER_ERROR, e);
        }
    }
}
