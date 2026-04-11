package com.example.ricms.repository;

import com.example.ricms.domain.entity.OutcomeEvidence;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface OutcomeEvidenceRepository extends JpaRepository<OutcomeEvidence, UUID> {

    List<OutcomeEvidence> findByOutcomeId(UUID outcomeId);
}
