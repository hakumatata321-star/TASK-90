package com.example.ricms.repository;

import com.example.ricms.domain.entity.ModerationQueue;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface ModerationQueueRepository extends JpaRepository<ModerationQueue, UUID> {

    long countByStatus(String status);
}
