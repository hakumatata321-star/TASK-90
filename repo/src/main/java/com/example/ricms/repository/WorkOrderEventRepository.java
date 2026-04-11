package com.example.ricms.repository;

import com.example.ricms.domain.entity.WorkOrderEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface WorkOrderEventRepository extends JpaRepository<WorkOrderEvent, UUID> {

    List<WorkOrderEvent> findByWorkOrderIdOrderByCreatedAtAsc(UUID workOrderId);
}
