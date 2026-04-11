package com.example.ricms.repository;

import com.example.ricms.domain.entity.OperationalParam;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface OperationalParamRepository extends JpaRepository<OperationalParam, String> {
}
