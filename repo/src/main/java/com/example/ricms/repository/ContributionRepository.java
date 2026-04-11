package com.example.ricms.repository;

import com.example.ricms.domain.entity.Contribution;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

@Repository
public interface ContributionRepository extends JpaRepository<Contribution, UUID> {

    List<Contribution> findByOutcomeId(UUID outcomeId);

    List<Contribution> findByOutcomeIdAndContributorUserId(UUID outcomeId, UUID contributorUserId);

    @Query("SELECT COALESCE(SUM(c.sharePercent), 0) FROM Contribution c WHERE c.outcomeId = :outcomeId")
    BigDecimal sumSharesByOutcomeId(@Param("outcomeId") UUID outcomeId);
}
