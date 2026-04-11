package com.example.ricms.repository;

import com.example.ricms.domain.entity.WorkOrderRating;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface WorkOrderRatingRepository extends JpaRepository<WorkOrderRating, UUID> {

    Optional<WorkOrderRating> findByWorkOrderId(UUID workOrderId);
}
