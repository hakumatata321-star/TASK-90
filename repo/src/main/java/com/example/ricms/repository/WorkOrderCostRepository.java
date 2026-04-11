package com.example.ricms.repository;

import com.example.ricms.domain.entity.WorkOrderCost;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface WorkOrderCostRepository extends JpaRepository<WorkOrderCost, UUID> {

    Optional<WorkOrderCost> findByWorkOrderId(UUID workOrderId);
}
