package com.example.ricms.repository;

import com.example.ricms.domain.entity.Coupon;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface CouponRepository extends JpaRepository<Coupon, UUID> {

    Optional<Coupon> findByCode(String code);

    @Query("SELECT c FROM Coupon c WHERE c.isActive = true AND " +
           "(c.activeFrom IS NULL OR c.activeFrom <= :now) AND " +
           "(c.activeTo IS NULL OR c.activeTo >= :now) ORDER BY c.priority DESC")
    List<Coupon> findActiveByNow(@Param("now") OffsetDateTime now);
}
