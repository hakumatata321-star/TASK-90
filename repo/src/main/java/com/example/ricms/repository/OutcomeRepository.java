package com.example.ricms.repository;

import com.example.ricms.domain.entity.Outcome;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface OutcomeRepository extends JpaRepository<Outcome, UUID> {

    List<Outcome> findByTitleNormalized(String titleNormalized);

    Optional<Outcome> findByCertificateNumber(String certificateNumber);

    Page<Outcome> findByProjectId(UUID projectId, Pageable pageable);

    /**
     * Returns outcomes whose abstract text is non-null.
     * Used as a pre-filter for Jaccard token overlap duplicate detection
     * to avoid loading outcomes with no abstract text.
     */
    @org.springframework.data.jpa.repository.Query(
        "SELECT o FROM Outcome o WHERE o.abstractText IS NOT NULL AND o.abstractText <> ''")
    List<Outcome> findWithAbstractText();
}
