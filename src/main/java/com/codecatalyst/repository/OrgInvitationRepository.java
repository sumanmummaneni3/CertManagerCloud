package com.codecatalyst.repository;

import com.codecatalyst.entity.OrgInvitation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface OrgInvitationRepository extends JpaRepository<OrgInvitation, UUID> {

    Optional<OrgInvitation> findByToken(String token);

    // Used by OAuth2UserService on first login to check for a pending invite
    Optional<OrgInvitation> findFirstByEmailAndUsedFalseAndExpiresAtAfter(String email, Instant now);

    // Check for a duplicate active invite for the same email+org
    boolean existsByOrganizationIdAndEmailAndUsedFalseAndExpiresAtAfter(UUID orgId, String email, Instant now);

    List<OrgInvitation> findAllByOrganizationId(UUID orgId);
}
