package com.example.ricms.repository;

import com.example.ricms.domain.entity.Report;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.UUID;

@Repository
public interface ReportRepository extends JpaRepository<Report, UUID> {

    long countByReporterUserIdAndCreatedAtAfter(UUID reporterUserId, OffsetDateTime after);
}
