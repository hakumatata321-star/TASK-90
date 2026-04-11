package com.example.ricms.repository;

import com.example.ricms.domain.entity.Like;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface LikeRepository extends JpaRepository<Like, UUID> {

    Optional<Like> findByUserIdAndContentTypeAndContentId(UUID userId, String contentType, UUID contentId);

    long countByContentTypeAndContentId(String contentType, UUID contentId);
}
