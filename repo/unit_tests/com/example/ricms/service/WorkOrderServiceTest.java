package com.example.ricms.service;

import com.example.ricms.domain.entity.WorkOrder;
import com.example.ricms.domain.entity.WorkOrderCost;
import com.example.ricms.domain.entity.WorkOrderRating;
import com.example.ricms.domain.enums.WorkOrderEventType;
import com.example.ricms.domain.enums.WorkOrderStatus;
import com.example.ricms.dto.request.WorkOrderCostRequest;
import com.example.ricms.dto.request.WorkOrderCreateRequest;
import com.example.ricms.dto.request.WorkOrderRatingRequest;
import com.example.ricms.dto.response.WorkOrderResponse;
import com.example.ricms.exception.AppException;
import com.example.ricms.repository.*;
import com.example.ricms.security.PermissionEnforcer;
import com.example.ricms.security.RicmsPrincipal;
import java.math.BigDecimal;
import java.util.Collection;
import java.util.Collections;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for WorkOrderService covering:
 *  - double-claim guard via pessimistic locking
 *  - full status progression (SUBMITTED → ASSIGNED → IN_PROGRESS → RESOLVED → CLOSED)
 *  - cost locking on RESOLUTION_CONFIRMED
 *  - rating submission and range enforcement
 *  - SLA timestamp tracking
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class WorkOrderServiceTest {

    @Mock WorkOrderRepository        workOrderRepository;
    @Mock WorkOrderEventRepository   workOrderEventRepository;
    @Mock WorkOrderCostRepository    workOrderCostRepository;
    @Mock WorkOrderRatingRepository  workOrderRatingRepository;
    @Mock AttachmentRepository       attachmentRepository;
    @Mock SlaService                 slaService;
    @Mock AuditService               auditService;
    @Mock PermissionEnforcer         permissionEnforcer;

    WorkOrderService service;

    private final UUID workOrderId   = UUID.randomUUID();
    private final UUID technicianA   = UUID.randomUUID();
    private final UUID technicianB   = UUID.randomUUID();
    private final UUID userId        = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new WorkOrderService(
                workOrderRepository, workOrderEventRepository,
                workOrderCostRepository, workOrderRatingRepository,
                attachmentRepository, slaService, auditService, permissionEnforcer);

        when(workOrderEventRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(workOrderRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(attachmentRepository.findByOwnerTypeAndOwnerId(any(), any())).thenReturn(Collections.emptyList());

        // Default security context: technicianA (matches assignedWorkOrder's technician)
        setUpSecurityContext(technicianA, List.of());
    }

    // ── Double-claim guard ────────────────────────────────────────────────────

    @Test
    void claimWorkOrder_alreadyClaimed_throws409() {
        WorkOrder wo = submittedWorkOrder();
        wo.setTechnicianUserId(technicianA);

        when(workOrderRepository.findByIdWithLock(workOrderId)).thenReturn(Optional.of(wo));

        assertThatThrownBy(() -> service.claimWorkOrder(workOrderId, technicianB))
                .isInstanceOf(AppException.class)
                .satisfies(e -> {
                    AppException ae = (AppException) e;
                    assertThat(ae.getCode()).isEqualTo("WORK_ORDER_ALREADY_CLAIMED");
                    assertThat(ae.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                });

        assertThat(wo.getTechnicianUserId()).isEqualTo(technicianA);
        assertThat(wo.getStatus()).isEqualTo(WorkOrderStatus.SUBMITTED);
        verify(workOrderRepository, never()).save(any());
    }

    @Test
    void claimWorkOrder_unclaimedSubmitted_assignsTechnicianAndTransitionsState() {
        WorkOrder wo = submittedWorkOrder();

        when(workOrderRepository.findByIdWithLock(workOrderId)).thenReturn(Optional.of(wo));

        WorkOrderResponse response = service.claimWorkOrder(workOrderId, technicianA);

        assertThat(wo.getTechnicianUserId()).isEqualTo(technicianA);
        assertThat(wo.getStatus()).isEqualTo(WorkOrderStatus.ASSIGNED);
        verify(workOrderRepository, atLeastOnce()).save(wo);
    }

    @Test
    void claimWorkOrder_notInSubmittedStatus_throws409() {
        WorkOrder wo = submittedWorkOrder();
        wo.setStatus(WorkOrderStatus.IN_PROGRESS);

        when(workOrderRepository.findByIdWithLock(workOrderId)).thenReturn(Optional.of(wo));

        assertThatThrownBy(() -> service.claimWorkOrder(workOrderId, technicianA))
                .isInstanceOf(AppException.class)
                .satisfies(e -> assertThat(((AppException) e).getStatus()).isEqualTo(HttpStatus.CONFLICT));

        verify(workOrderRepository, never()).save(any());
    }

    // ── Status progression: FIRST_RESPONSE_SENT ──────────────────────────────

    @Test
    void addEvent_firstResponseSent_setsFirstRespondedAt() {
        WorkOrder wo = assignedWorkOrder();
        when(workOrderRepository.findById(workOrderId)).thenReturn(Optional.of(wo));

        service.addEvent(workOrderId, WorkOrderEventType.FIRST_RESPONSE_SENT, "Diagnosis started", technicianA);

        assertThat(wo.getFirstRespondedAt()).isNotNull();
        verify(workOrderRepository).save(wo);
    }

    @Test
    void addEvent_firstResponseSent_idempotent_doesNotOverwrite() {
        WorkOrder wo = assignedWorkOrder();
        OffsetDateTime originalTime = OffsetDateTime.now().minusHours(1);
        wo.setFirstRespondedAt(originalTime);
        when(workOrderRepository.findById(workOrderId)).thenReturn(Optional.of(wo));

        service.addEvent(workOrderId, WorkOrderEventType.FIRST_RESPONSE_SENT, "Second response", technicianA);

        assertThat(wo.getFirstRespondedAt()).isEqualTo(originalTime);
    }

    // ── Status progression: STATUS_UPDATED → IN_PROGRESS ─────────────────────

    @Test
    void addEvent_statusUpdated_transitionsToInProgress() {
        WorkOrder wo = assignedWorkOrder();
        when(workOrderRepository.findById(workOrderId)).thenReturn(Optional.of(wo));

        service.addEvent(workOrderId, WorkOrderEventType.STATUS_UPDATED, "Working on repair", technicianA);

        assertThat(wo.getStatus()).isEqualTo(WorkOrderStatus.IN_PROGRESS);
    }

    // ── Status progression: RESOLUTION_CONFIRMED → RESOLVED + cost lock ──────

    @Test
    void addEvent_resolutionConfirmed_transitionsToResolvedAndLocksCost() {
        WorkOrder wo = assignedWorkOrder();
        wo.setStatus(WorkOrderStatus.IN_PROGRESS);
        when(workOrderRepository.findById(workOrderId)).thenReturn(Optional.of(wo));

        WorkOrderCost cost = WorkOrderCost.builder()
                .workOrderId(workOrderId)
                .partsCost(new BigDecimal("150.00"))
                .laborCost(new BigDecimal("80.00"))
                .build();
        when(workOrderCostRepository.findByWorkOrderId(workOrderId)).thenReturn(Optional.of(cost));

        service.addEvent(workOrderId, WorkOrderEventType.RESOLUTION_CONFIRMED, "Issue resolved", technicianA);

        assertThat(wo.getStatus()).isEqualTo(WorkOrderStatus.RESOLVED);
        assertThat(wo.getResolvedAt()).isNotNull();
        assertThat(cost.getLockedAt()).isNotNull();
        assertThat(cost.getApprovedStatus()).isEqualTo("APPROVED");
        verify(workOrderCostRepository).save(cost);
    }

    @Test
    void addEvent_resolutionConfirmed_alreadyLockedCost_doesNotRelock() {
        WorkOrder wo = assignedWorkOrder();
        wo.setStatus(WorkOrderStatus.IN_PROGRESS);
        when(workOrderRepository.findById(workOrderId)).thenReturn(Optional.of(wo));

        OffsetDateTime originalLock = OffsetDateTime.now().minusDays(1);
        WorkOrderCost cost = WorkOrderCost.builder()
                .workOrderId(workOrderId)
                .partsCost(BigDecimal.TEN)
                .laborCost(BigDecimal.TEN)
                .lockedAt(originalLock)
                .approvedStatus("APPROVED")
                .build();
        when(workOrderCostRepository.findByWorkOrderId(workOrderId)).thenReturn(Optional.of(cost));

        service.addEvent(workOrderId, WorkOrderEventType.RESOLUTION_CONFIRMED, "Resolved again", technicianA);

        assertThat(cost.getLockedAt()).isEqualTo(originalLock);
    }

    // ── Cost management ──────────────────────────────────────────────────────

    @Test
    void updateCost_onAssignedWorkOrder_succeeds() {
        WorkOrder wo = assignedWorkOrder();
        when(workOrderRepository.findById(workOrderId)).thenReturn(Optional.of(wo));
        when(workOrderCostRepository.findByWorkOrderId(workOrderId)).thenReturn(Optional.empty());
        when(workOrderCostRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        WorkOrderCostRequest request = new WorkOrderCostRequest();
        request.setPartsCost(new BigDecimal("200.00"));
        request.setLaborCost(new BigDecimal("100.00"));
        request.setNotes("Screen replacement");

        assertThatCode(() -> service.updateCost(workOrderId, request, technicianA))
                .doesNotThrowAnyException();

        verify(workOrderCostRepository).save(any());
    }

    @Test
    void updateCost_onResolvedWorkOrder_throws409() {
        WorkOrder wo = assignedWorkOrder();
        wo.setStatus(WorkOrderStatus.RESOLVED);
        when(workOrderRepository.findById(workOrderId)).thenReturn(Optional.of(wo));

        WorkOrderCostRequest request = new WorkOrderCostRequest();
        request.setPartsCost(new BigDecimal("50.00"));

        assertThatThrownBy(() -> service.updateCost(workOrderId, request, technicianA))
                .isInstanceOf(AppException.class)
                .satisfies(e -> {
                    AppException ae = (AppException) e;
                    assertThat(ae.getCode()).isEqualTo("COST_LOCKED");
                    assertThat(ae.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                });
    }

    @Test
    void updateCost_onClosedWorkOrder_throws409() {
        WorkOrder wo = assignedWorkOrder();
        wo.setStatus(WorkOrderStatus.CLOSED);
        when(workOrderRepository.findById(workOrderId)).thenReturn(Optional.of(wo));

        WorkOrderCostRequest request = new WorkOrderCostRequest();
        request.setPartsCost(BigDecimal.TEN);

        assertThatThrownBy(() -> service.updateCost(workOrderId, request, technicianA))
                .isInstanceOf(AppException.class)
                .satisfies(e -> assertThat(((AppException) e).getCode()).isEqualTo("COST_LOCKED"));
    }

    @Test
    void updateCost_alreadyLockedCost_throws409() {
        WorkOrder wo = assignedWorkOrder();
        when(workOrderRepository.findById(workOrderId)).thenReturn(Optional.of(wo));

        WorkOrderCost lockedCost = WorkOrderCost.builder()
                .workOrderId(workOrderId)
                .partsCost(BigDecimal.TEN)
                .laborCost(BigDecimal.TEN)
                .lockedAt(OffsetDateTime.now())
                .build();
        when(workOrderCostRepository.findByWorkOrderId(workOrderId)).thenReturn(Optional.of(lockedCost));

        WorkOrderCostRequest request = new WorkOrderCostRequest();
        request.setPartsCost(new BigDecimal("999.00"));

        assertThatThrownBy(() -> service.updateCost(workOrderId, request, technicianA))
                .isInstanceOf(AppException.class)
                .satisfies(e -> assertThat(((AppException) e).getCode()).isEqualTo("COST_LOCKED"));
    }

    // ── Rating ───────────────────────────────────────────────────────────────

    @Test
    void submitRating_onResolvedWorkOrder_transitionsToClosed() {
        WorkOrder wo = assignedWorkOrder();
        wo.setStatus(WorkOrderStatus.RESOLVED);
        when(workOrderRepository.findById(workOrderId)).thenReturn(Optional.of(wo));
        when(workOrderRatingRepository.findByWorkOrderId(workOrderId)).thenReturn(Optional.empty());
        when(workOrderRatingRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        WorkOrderRatingRequest request = new WorkOrderRatingRequest();
        request.setRating(5);
        request.setComment("Excellent service!");

        // Use technicianA (the assigned tech) as rater for authorization
        service.submitRating(workOrderId, request, technicianA);

        assertThat(wo.getStatus()).isEqualTo(WorkOrderStatus.CLOSED);
        verify(workOrderRatingRepository).save(any());
        verify(workOrderRepository).save(wo);
    }

    @Test
    void submitRating_alreadyRated_throws409() {
        WorkOrder wo = assignedWorkOrder();
        wo.setStatus(WorkOrderStatus.RESOLVED);
        when(workOrderRepository.findById(workOrderId)).thenReturn(Optional.of(wo));
        when(workOrderRatingRepository.findByWorkOrderId(workOrderId))
                .thenReturn(Optional.of(new WorkOrderRating()));

        WorkOrderRatingRequest request = new WorkOrderRatingRequest();
        request.setRating(3);

        assertThatThrownBy(() -> service.submitRating(workOrderId, request, technicianA))
                .isInstanceOf(AppException.class)
                .satisfies(e -> {
                    AppException ae = (AppException) e;
                    assertThat(ae.getCode()).isEqualTo("ALREADY_RATED");
                    assertThat(ae.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                });
    }

    @Test
    void submitRating_savesCorrectRatingValue() {
        WorkOrder wo = assignedWorkOrder();
        wo.setStatus(WorkOrderStatus.RESOLVED);
        when(workOrderRepository.findById(workOrderId)).thenReturn(Optional.of(wo));
        when(workOrderRatingRepository.findByWorkOrderId(workOrderId)).thenReturn(Optional.empty());
        when(workOrderRatingRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        WorkOrderRatingRequest request = new WorkOrderRatingRequest();
        request.setRating(1);
        request.setComment("Poor service");

        service.submitRating(workOrderId, request, technicianA);

        ArgumentCaptor<WorkOrderRating> cap = ArgumentCaptor.forClass(WorkOrderRating.class);
        verify(workOrderRatingRepository).save(cap.capture());
        assertThat(cap.getValue().getRating()).isEqualTo(1);
        assertThat(cap.getValue().getComment()).isEqualTo("Poor service");
        assertThat(cap.getValue().getRaterUserId()).isEqualTo(technicianA);
    }

    // ── Work order creation with SLA ─────────────────────────────────────────

    @Test
    void createWorkOrder_setsSlaTimestamps() {
        OffsetDateTime expectedFirstResponse = OffsetDateTime.now().plusHours(4);
        OffsetDateTime expectedResolution = OffsetDateTime.now().plusDays(3);
        when(slaService.computeFirstResponseDue(any())).thenReturn(expectedFirstResponse);
        when(slaService.computeResolutionDue(any())).thenReturn(expectedResolution);
        when(workOrderRepository.save(any())).thenAnswer(inv -> {
            WorkOrder w = inv.getArgument(0);
            try {
                var f = WorkOrder.class.getDeclaredField("id");
                f.setAccessible(true);
                f.set(w, UUID.randomUUID());
            } catch (Exception e) { /* ignore */ }
            return w;
        });

        WorkOrderCreateRequest request = new WorkOrderCreateRequest();
        request.setDescription("Screen broken");

        WorkOrderResponse response = service.createWorkOrder(request, userId);

        assertThat(response.getSlaFirstResponseDueAt()).isEqualTo(expectedFirstResponse);
        assertThat(response.getSlaResolutionDueAt()).isEqualTo(expectedResolution);
    }

    // ── Object-level authorization (Fix 2) ─────────────────────────────────

    @Test
    void addEvent_byNonAssignedNonAdmin_throws403() {
        WorkOrder wo = assignedWorkOrder(); // assigned to technicianA
        when(workOrderRepository.findById(workOrderId)).thenReturn(Optional.of(wo));

        // technicianB is not assigned and has no admin permissions
        setUpSecurityContext(technicianB, List.of());

        assertThatThrownBy(() -> service.addEvent(workOrderId, WorkOrderEventType.FIRST_RESPONSE_SENT, "hack", technicianB))
                .isInstanceOf(AppException.class)
                .satisfies(e -> {
                    AppException ae = (AppException) e;
                    assertThat(ae.getCode()).isEqualTo("FORBIDDEN");
                    assertThat(ae.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
                });
    }

    @Test
    void addEvent_byAssignedTechnician_succeeds() {
        WorkOrder wo = assignedWorkOrder(); // assigned to technicianA
        when(workOrderRepository.findById(workOrderId)).thenReturn(Optional.of(wo));

        // technicianA is the assigned technician
        setUpSecurityContext(technicianA, List.of());

        assertThatCode(() -> service.addEvent(workOrderId, WorkOrderEventType.FIRST_RESPONSE_SENT, "ok", technicianA))
                .doesNotThrowAnyException();
    }

    @Test
    void updateCost_byNonAssignedNonAdmin_throws403() {
        WorkOrder wo = assignedWorkOrder();
        when(workOrderRepository.findById(workOrderId)).thenReturn(Optional.of(wo));
        setUpSecurityContext(technicianB, List.of());

        WorkOrderCostRequest request = new WorkOrderCostRequest();
        request.setPartsCost(new BigDecimal("100.00"));

        assertThatThrownBy(() -> service.updateCost(workOrderId, request, technicianB))
                .isInstanceOf(AppException.class)
                .satisfies(e -> assertThat(((AppException) e).getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
    }

    @Test
    void submitRating_byNonAssignedNonAdmin_throws403() {
        WorkOrder wo = assignedWorkOrder();
        wo.setStatus(WorkOrderStatus.RESOLVED);
        when(workOrderRepository.findById(workOrderId)).thenReturn(Optional.of(wo));
        setUpSecurityContext(technicianB, List.of());

        WorkOrderRatingRequest request = new WorkOrderRatingRequest();
        request.setRating(5);

        assertThatThrownBy(() -> service.submitRating(workOrderId, request, technicianB))
                .isInstanceOf(AppException.class)
                .satisfies(e -> assertThat(((AppException) e).getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    private void setUpSecurityContext(UUID ctxUserId, Collection<? extends GrantedAuthority> authorities) {
        RicmsPrincipal principal = new RicmsPrincipal(ctxUserId, "user-" + ctxUserId, "", authorities);
        var auth = new UsernamePasswordAuthenticationToken(principal, null, authorities);
        var ctx = SecurityContextHolder.createEmptyContext();
        ctx.setAuthentication(auth);
        SecurityContextHolder.setContext(ctx);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private WorkOrder submittedWorkOrder() {
        WorkOrder wo = new WorkOrder();
        try {
            var idField = WorkOrder.class.getDeclaredField("id");
            idField.setAccessible(true);
            idField.set(wo, workOrderId);
            var caField = WorkOrder.class.getDeclaredField("createdAt");
            caField.setAccessible(true);
            caField.set(wo, OffsetDateTime.now().minusMinutes(5));
        } catch (Exception e) { /* ignore */ }
        wo.setStatus(WorkOrderStatus.SUBMITTED);
        wo.setWorkOrderNumber("WO-TEST-001");
        return wo;
    }

    private WorkOrder assignedWorkOrder() {
        WorkOrder wo = submittedWorkOrder();
        wo.setStatus(WorkOrderStatus.ASSIGNED);
        wo.setTechnicianUserId(technicianA);
        return wo;
    }
}
