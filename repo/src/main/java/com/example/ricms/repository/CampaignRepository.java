package com.example.ricms.repository;

import com.example.ricms.domain.entity.Campaign;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Repository
public interface CampaignRepository extends JpaRepository<Campaign, UUID> {

    @Query("SELECT c FROM Campaign c WHERE c.isActive = true AND " +
           "(c.activeFrom IS NULL OR c.activeFrom <= :now) AND " +
           "(c.activeTo IS NULL OR c.activeTo >= :now) ORDER BY c.priority DESC")
    List<Campaign> findActiveCampaignsByNow(@Param("now") OffsetDateTime now);
}
