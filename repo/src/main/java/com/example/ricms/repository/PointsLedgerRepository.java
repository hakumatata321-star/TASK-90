package com.example.ricms.repository;

import com.example.ricms.domain.entity.PointsLedger;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface PointsLedgerRepository extends JpaRepository<PointsLedger, UUID> {

    Page<PointsLedger> findByMemberIdOrderByCreatedAtDesc(UUID memberId, Pageable pageable);

    @Query("SELECT COALESCE(SUM(p.pointsDelta), 0) FROM PointsLedger p WHERE p.memberId = :memberId")
    Long sumPointsByMemberId(@Param("memberId") UUID memberId);
}
