package com.codecatalyst.entity;

import com.codecatalyst.enums.UserRole;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "\"user\"")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(updatable = false, nullable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "org_id", nullable = false)
    private Organization organization;

    @Column(nullable = false, unique = true, length = 320)
    private String email;

    /** Display name from Google profile */
    @Column(length = 255)
    private String name;

    /**
     * Google's stable subject identifier (the "sub" claim from the ID token).
     * Never changes even if the user changes their email address.
     * Used as the primary identity key for OAuth2 lookups.
     */
    @Column(name = "google_sub", nullable = false, unique = true, length = 255)
    private String googleSub;

    // UserRoleConverter (autoApply = true) handles VARCHAR ↔ enum mapping
    @Column(nullable = false)
    private UserRole role;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;
}
