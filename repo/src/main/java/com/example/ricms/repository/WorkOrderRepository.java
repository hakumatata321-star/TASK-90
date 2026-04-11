package com.example.ricms.repository;

import com.example.ricms.domain.entity.WorkOrder;
import com.example.ricms.domain.enums.WorkOrderStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import jakarta.persistence.LockModeType;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface WorkOrderRepository extends JpaRepository<WorkOrder, UUID> {

    Optional<WorkOrder> findByWorkOrderNumber(String workOrderNumber);

    Page<WorkOrder> findByTechnicianUserId(UUID technicianUserId, Pageable pageable);

    Page<WorkOrder> findByStatus(WorkOrderStatus status, Pageable pageable);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT w FROM WorkOrder w WHERE w.id = :id")
    Optional<WorkOrder> findByIdWithLock(@Param("id") UUID id);

    @Query("SELECT w FROM WorkOrder w WHERE " +
           "(:technicianId IS NULL OR w.technicianUserId = :technicianId) AND " +
           "(:status IS NULL OR w.status = :status) AND " +
           "w.createdAt >= :from AND w.createdAt <= :to")
    List<WorkOrder> findForAnalytics(
            @Param("technicianId") UUID technicianId,
            @Param("status") WorkOrderStatus status,
            @Param("from") OffsetDateTime from,
            @Param("to") OffsetDateTime to);

    long countByStatus(WorkOrderStatus status);
}
