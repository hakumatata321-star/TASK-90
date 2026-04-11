package com.example.ricms.domain.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.OffsetDateTime;

@Entity
@Table(name = "inventory")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(of = "sku")
public class Inventory {

    @Id
    @Column(length = 100)
    private String sku;

    @Column(name = "on_hand", nullable = false)
    private int onHand = 0;

    @Column(nullable = false)
    private int reserved = 0;

    @Column(name = "reserved_until", columnDefinition = "TIMESTAMPTZ")
    private OffsetDateTime reservedUntil;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false, columnDefinition = "TIMESTAMPTZ")
    private OffsetDateTime updatedAt;
}
