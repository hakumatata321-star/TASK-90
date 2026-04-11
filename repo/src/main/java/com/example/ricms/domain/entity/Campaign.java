package com.example.ricms.domain.entity;

import com.example.ricms.domain.enums.CampaignType;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "campaigns")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(of = "id")
public class Campaign {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    @Column(columnDefinition = "uuid DEFAULT gen_random_uuid()")
    private UUID id;

    @Column(nullable = false, length = 100)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private CampaignType type;

    @Column(columnDefinition = "JSONB")
    private String params;

    @Column(nullable = false)
    private int priority = 0;

    @Column(name = "active_from", columnDefinition = "TIMESTAMPTZ")
    private OffsetDateTime activeFrom;

    @Column(name = "active_to", columnDefinition = "TIMESTAMPTZ")
    private OffsetDateTime activeTo;

    @Column(name = "is_active", nullable = false)
    private boolean isActive = true;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, columnDefinition = "TIMESTAMPTZ")
    private OffsetDateTime createdAt;
}
