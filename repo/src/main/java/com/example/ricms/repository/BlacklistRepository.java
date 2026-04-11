package com.example.ricms.repository;

import com.example.ricms.domain.entity.Blacklist;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface BlacklistRepository extends JpaRepository<Blacklist, UUID> {

    @Query("SELECT b FROM Blacklist b WHERE b.userId = :userId AND " +
           "(b.expiresAt IS NULL OR b.expiresAt > :now)")
    Optional<Blacklist> findActiveByUserId(@Param("userId") UUID userId, @Param("now") OffsetDateTime now);
}
