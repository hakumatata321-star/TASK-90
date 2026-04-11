package com.example.ricms.service;

import com.example.ricms.domain.entity.*;
import com.example.ricms.domain.enums.WorkOrderEventType;
import com.example.ricms.domain.enums.WorkOrderStatus;
import com.example.ricms.dto.request.WorkOrderCostRequest;
import com.example.ricms.dto.request.WorkOrderCreateRequest;
import com.example.ricms.dto.request.WorkOrderRatingRequest;
import com.example.ricms.dto.response.AttachmentResponse;
import com.example.ricms.dto.response.WorkOrderAnalyticsResponse;
import com.example.ricms.dto.response.WorkOrderResponse;
import com.example.ricms.exception.AppException;
import com.example.ricms.repository.*;
import com.example.ricms.security.PermissionEnforcer;
import com.example.ricms.security.SecurityUtils;
import com.opencsv.CSVWriter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.StringWriter;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class WorkOrderService {

    private final WorkOrderRepository workOrderRepository;
    private final WorkOrderEventRepository workOrderEventRepository;
    private final WorkOrderCostRepository workOrderCostRepository;
    private final WorkOrderRatingRepository workOrderRatingRepository;
    private final AttachmentRepository attachmentRepository;
    private final SlaService slaService;
    private final AuditService auditService;
    private final PermissionEnforcer permissionEnforcer;

    private static final AtomicLong woSeq = new AtomicLong(System.currentTimeMillis() % 100000);

    /**
     * Object-level authorization: the caller must be the assigned technician
     * or hold ADMIN:WRITE. Prevents cross-user mutation of work orders.
     */
    private void assertWorkOrderWriteAccess(WorkOrder wo, UUID actorId) {
        boolean isAssigned = actorId.equals(wo.getTechnicianUserId());
        if (!isAssigned && !SecurityUtils.hasPermission("ADMIN", "WRITE")) {
            throw new AppException("FORBIDDEN",
                    "You do not have write access to this work order", HttpStatus.FORBIDDEN);
        }
    }

    @Transactional
    public WorkOrderResponse createWorkOrder(WorkOrderCreateRequest request, UUID actorId) {
        permissionEnforcer.require("WORK_ORDER", "WRITE");
        String woNumber = "WO-" + System.currentTimeMillis() + "-" + woSeq.incrementAndGet();
        OffsetDateTime now = OffsetDateTime.now();

        WorkOrder workOrder = WorkOrder.builder()
                .workOrderNumber(woNumber)
                .orderId(request.getOrderId())
                .status(WorkOrderStatus.SUBMITTED)
                .description(request.getDescription())
                .slaFirstResponseDueAt(slaService.computeFirstResponseDue(now))
                .slaResolutionDueAt(slaService.computeResolutionDue(now))
                .build();

        workOrder = workOrderRepository.save(workOrder);

        // E) Persist attachments supplied at creation time
        if (request.getAttachments() != null) {
            for (var attachmentDto : request.getAttachments()) {
                Attachment attachment = Attachment.builder()
                        .ownerType("WORK_ORDER")
                        .ownerId(workOrder.getId())
                        .blobRef(attachmentDto.getBlobRef())
                        .contentType(attachmentDto.getContentType())
                        .build();
                attachmentRepository.save(attachment);
            }
        }

        auditService.record(actorId, "WORK_ORDER", workOrder.getId().toString(),
                "CREATE", "USER_ACTION", null, woNumber);

        return toResponse(workOrder);
    }

    public WorkOrderResponse getWorkOrder(UUID workOrderId) {
        permissionEnforcer.require("WORK_ORDER", "READ");
        WorkOrder wo = workOrderRepository.findById(workOrderId)
                .orElseThrow(() -> new AppException("WORK_ORDER_NOT_FOUND", "Work order not found", HttpStatus.NOT_FOUND));
        return toResponse(wo);
    }

    @Transactional
    public WorkOrderResponse claimWorkOrder(UUID workOrderId, UUID technicianId) {
        permissionEnforcer.require("WORK_ORDER", "WRITE");
        WorkOrder wo = workOrderRepository.findByIdWithLock(workOrderId)
                .orElseThrow(() -> new AppException("WORK_ORDER_NOT_FOUND", "Work order not found", HttpStatus.NOT_FOUND));

        if (wo.getTechnicianUserId() != null) {
            throw new AppException("WORK_ORDER_ALREADY_CLAIMED",
                    "Work order is already claimed by another technician", HttpStatus.CONFLICT);
        }
        if (wo.getStatus() != WorkOrderStatus.SUBMITTED) {
            throw new AppException("INVALID_STATE", "Work order is not in SUBMITTED status", HttpStatus.CONFLICT);
        }

        wo.setTechnicianUserId(technicianId);
        wo.setStatus(WorkOrderStatus.ASSIGNED);
        workOrderRepository.save(wo);

        addEventInternal(wo.getId(), WorkOrderEventType.TECHNICIAN_ASSIGNED,
                "Claimed by technician " + technicianId, technicianId);

        auditService.record(technicianId, "WORK_ORDER", workOrderId.toString(),
                "CLAIM_WORK_ORDER", "USER_ACTION",
                Map.of("status", WorkOrderStatus.SUBMITTED.name()),
                Map.of("status", WorkOrderStatus.ASSIGNED.name(), "technicianId", technicianId.toString()));

        return toResponse(wo);
    }

    @Transactional
    public WorkOrderResponse addEvent(UUID workOrderId, WorkOrderEventType type, String payload, UUID actorId) {
        permissionEnforcer.require("WORK_ORDER", "WRITE");
        WorkOrder wo = workOrderRepository.findById(workOrderId)
                .orElseThrow(() -> new AppException("WORK_ORDER_NOT_FOUND", "Work order not found", HttpStatus.NOT_FOUND));
        assertWorkOrderWriteAccess(wo, actorId);

        addEventInternal(workOrderId, type, payload, actorId);

        if (type == WorkOrderEventType.FIRST_RESPONSE_SENT && wo.getFirstRespondedAt() == null) {
            wo.setFirstRespondedAt(OffsetDateTime.now());
        }
        if (type == WorkOrderEventType.RESOLUTION_CONFIRMED) {
            wo.setResolvedAt(OffsetDateTime.now());
            wo.setStatus(WorkOrderStatus.RESOLVED);
            // Q12: auto-lock cost on resolution so it can no longer be edited
            workOrderCostRepository.findByWorkOrderId(workOrderId).ifPresent(cost -> {
                if (cost.getLockedAt() == null) {
                    cost.setLockedAt(OffsetDateTime.now());
                    cost.setApprovedStatus("APPROVED");
                    workOrderCostRepository.save(cost);
                }
            });
        }
        if (type == WorkOrderEventType.STATUS_UPDATED) {
            // Keep current status unless specified
            wo.setStatus(WorkOrderStatus.IN_PROGRESS);
        }
        workOrderRepository.save(wo);

        return toResponse(wo);
    }

    private void addEventInternal(UUID workOrderId, WorkOrderEventType type, String payload, UUID actorId) {
        WorkOrderEvent event = WorkOrderEvent.builder()
                .workOrderId(workOrderId)
                .eventType(type)
                .payload(payload)
                .actorUserId(actorId)
                .build();
        workOrderEventRepository.save(event);
    }

    @Transactional
    public WorkOrderResponse updateCost(UUID workOrderId, WorkOrderCostRequest request, UUID actorId) {
        permissionEnforcer.require("WORK_ORDER", "WRITE");
        WorkOrder wo = workOrderRepository.findById(workOrderId)
                .orElseThrow(() -> new AppException("WORK_ORDER_NOT_FOUND", "Work order not found", HttpStatus.NOT_FOUND));
        assertWorkOrderWriteAccess(wo, actorId);

        // Q12: cost is editable only while the work order is in early states
        if (wo.getStatus() == WorkOrderStatus.RESOLVED || wo.getStatus() == WorkOrderStatus.CLOSED) {
            throw new AppException("COST_LOCKED",
                    "Work order cost cannot be modified in status: " + wo.getStatus(),
                    HttpStatus.CONFLICT);
        }

        WorkOrderCost cost = workOrderCostRepository.findByWorkOrderId(workOrderId)
                .orElseGet(() -> WorkOrderCost.builder()
                        .workOrderId(workOrderId)
                        .partsCost(BigDecimal.ZERO)
                        .laborCost(BigDecimal.ZERO)
                        .build());

        if (cost.getLockedAt() != null) {
            throw new AppException("COST_LOCKED", "Work order cost is locked and cannot be modified", HttpStatus.CONFLICT);
        }

        BigDecimal prevParts = cost.getPartsCost();
        BigDecimal prevLabor = cost.getLaborCost();

        if (request.getPartsCost() != null) cost.setPartsCost(request.getPartsCost());
        if (request.getLaborCost() != null) cost.setLaborCost(request.getLaborCost());
        if (request.getNotes() != null) cost.setNotes(request.getNotes());
        workOrderCostRepository.save(cost);

        addEventInternal(workOrderId, WorkOrderEventType.COST_UPDATED, "Cost updated", actorId);

        auditService.record(actorId, "WORK_ORDER", workOrderId.toString(),
                "UPDATE_COST", "USER_ACTION",
                Map.of("partsCost", prevParts.toPlainString(), "laborCost", prevLabor.toPlainString()),
                Map.of("partsCost", cost.getPartsCost().toPlainString(), "laborCost", cost.getLaborCost().toPlainString()));

        return toResponse(wo);
    }

    @Transactional
    public WorkOrderResponse submitRating(UUID workOrderId, WorkOrderRatingRequest request, UUID userId) {
        permissionEnforcer.require("WORK_ORDER", "WRITE");
        WorkOrder wo = workOrderRepository.findById(workOrderId)
                .orElseThrow(() -> new AppException("WORK_ORDER_NOT_FOUND", "Work order not found", HttpStatus.NOT_FOUND));
        assertWorkOrderWriteAccess(wo, userId);

        if (workOrderRatingRepository.findByWorkOrderId(workOrderId).isPresent()) {
            throw new AppException("ALREADY_RATED", "This work order has already been rated", HttpStatus.CONFLICT);
        }

        WorkOrderRating rating = WorkOrderRating.builder()
                .workOrderId(workOrderId)
                .raterUserId(userId)
                .rating(request.getRating())
                .comment(request.getComment())
                .build();
        workOrderRatingRepository.save(rating);

        if (wo.getStatus() == WorkOrderStatus.RESOLVED) {
            wo.setStatus(WorkOrderStatus.CLOSED);
            workOrderRepository.save(wo);
        }

        auditService.record(userId, "WORK_ORDER", workOrderId.toString(),
                "SUBMIT_RATING", "USER_ACTION",
                null, Map.of("rating", String.valueOf(request.getRating())));

        return toResponse(wo);
    }

    public WorkOrderAnalyticsResponse getAnalytics(LocalDate from, LocalDate to,
                                                    UUID technicianId, String statusStr) {
        // RLS: callers without ADMIN:READ can only see their own work orders
        if (!SecurityUtils.hasPermission("ADMIN", "READ")) {
            technicianId = SecurityUtils.getCurrentUserId();
        }
        WorkOrderStatus status = null;
        if (statusStr != null && !statusStr.isBlank()) {
            try { status = WorkOrderStatus.valueOf(statusStr); } catch (Exception e) { /* ignore */ }
        }
        OffsetDateTime fromDt = from != null
                ? from.atStartOfDay().atOffset(ZoneOffset.UTC)
                : OffsetDateTime.now().minusYears(10);
        OffsetDateTime toDt = to != null
                ? to.plusDays(1).atStartOfDay().atOffset(ZoneOffset.UTC)
                : OffsetDateTime.now().plusDays(1);

        List<WorkOrder> orders = workOrderRepository.findForAnalytics(technicianId, status, fromDt, toDt);

        long submitted = orders.stream().filter(w -> w.getStatus() == WorkOrderStatus.SUBMITTED).count();
        long assigned = orders.stream().filter(w -> w.getStatus() == WorkOrderStatus.ASSIGNED).count();
        long inProgress = orders.stream().filter(w -> w.getStatus() == WorkOrderStatus.IN_PROGRESS).count();
        long resolved = orders.stream().filter(w -> w.getStatus() == WorkOrderStatus.RESOLVED).count();
        long closed = orders.stream().filter(w -> w.getStatus() == WorkOrderStatus.CLOSED).count();

        double avgRating = orders.stream()
                .mapToInt(w -> workOrderRatingRepository.findByWorkOrderId(w.getId())
                        .map(WorkOrderRating::getRating).orElse(0))
                .filter(r -> r > 0)
                .average().orElse(0.0);

        BigDecimal totalParts = orders.stream()
                .map(w -> workOrderCostRepository.findByWorkOrderId(w.getId())
                        .map(WorkOrderCost::getPartsCost).orElse(BigDecimal.ZERO))
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal totalLabor = orders.stream()
                .map(w -> workOrderCostRepository.findByWorkOrderId(w.getId())
                        .map(WorkOrderCost::getLaborCost).orElse(BigDecimal.ZERO))
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        return WorkOrderAnalyticsResponse.builder()
                .total(orders.size())
                .submitted(submitted)
                .assigned(assigned)
                .inProgress(inProgress)
                .resolved(resolved)
                .closed(closed)
                .avgRating(avgRating)
                .totalPartsCost(totalParts)
                .totalLaborCost(totalLabor)
                .build();
    }

    public byte[] exportCsv() {
        permissionEnforcer.require("ADMIN", "READ");
        List<WorkOrder> all = workOrderRepository.findAll();
        try (StringWriter sw = new StringWriter(); CSVWriter writer = new CSVWriter(sw)) {
            writer.writeNext(new String[]{"ID", "Number", "Status", "Technician", "Created"});
            for (WorkOrder wo : all) {
                writer.writeNext(new String[]{
                        wo.getId().toString(),
                        wo.getWorkOrderNumber(),
                        wo.getStatus().name(),
                        wo.getTechnicianUserId() != null ? wo.getTechnicianUserId().toString() : "",
                        wo.getCreatedAt().toString()
                });
            }
            return sw.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new AppException("EXPORT_FAILED", "Failed to export CSV", HttpStatus.INTERNAL_SERVER_ERROR, e);
        }
    }

    // E) Include persisted attachments in every work-order response
    public WorkOrderResponse toResponse(WorkOrder wo) {
        List<AttachmentResponse> attachments = Collections.emptyList();
        if (wo.getId() != null) {
            attachments = attachmentRepository.findByOwnerTypeAndOwnerId("WORK_ORDER", wo.getId())
                    .stream()
                    .map(a -> AttachmentResponse.builder()
                            .id(a.getId())
                            .ownerType(a.getOwnerType())
                            .ownerId(a.getOwnerId())
                            .blobRef(a.getBlobRef())
                            .contentType(a.getContentType())
                            .createdAt(a.getCreatedAt())
                            .build())
                    .collect(Collectors.toList());
        }
        return WorkOrderResponse.builder()
                .id(wo.getId())
                .workOrderNumber(wo.getWorkOrderNumber())
                .orderId(wo.getOrderId())
                .technicianUserId(wo.getTechnicianUserId())
                .status(wo.getStatus())
                .description(wo.getDescription())
                .slaFirstResponseDueAt(wo.getSlaFirstResponseDueAt())
                .slaResolutionDueAt(wo.getSlaResolutionDueAt())
                .firstRespondedAt(wo.getFirstRespondedAt())
                .resolvedAt(wo.getResolvedAt())
                .createdAt(wo.getCreatedAt())
                .attachments(attachments)
                .build();
    }
}
