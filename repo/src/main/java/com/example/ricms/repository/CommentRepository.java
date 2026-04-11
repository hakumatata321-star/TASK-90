package com.example.ricms.repository;

import com.example.ricms.domain.entity.Comment;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.UUID;

@Repository
public interface CommentRepository extends JpaRepository<Comment, UUID> {

    Page<Comment> findByContentTypeAndContentIdOrderByCreatedAtDesc(
            String contentType, UUID contentId, Pageable pageable);

    long countByAuthorUserIdAndCreatedAtAfter(UUID authorUserId, OffsetDateTime after);
}
