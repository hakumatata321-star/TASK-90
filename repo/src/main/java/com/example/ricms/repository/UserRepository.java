package com.example.ricms.repository;

import com.example.ricms.domain.entity.User;
import com.example.ricms.domain.enums.UserStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface UserRepository extends JpaRepository<User, UUID> {

    Optional<User> findByUsername(String username);

    Page<User> findByStatus(UserStatus status, Pageable pageable);

    @Query("SELECT u FROM User u WHERE (:q IS NULL OR LOWER(u.username) LIKE LOWER(CONCAT('%', :q, '%')))")
    Page<User> searchUsers(@Param("q") String q, Pageable pageable);
}
