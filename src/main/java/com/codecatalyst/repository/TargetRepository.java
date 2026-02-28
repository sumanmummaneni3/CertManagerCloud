package com.codecatalyst.repository;


import com.codecatalyst.entity.Target;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface TargetRepository extends JpaRepository<Target, UUID> {
    List<Target> findByOrganizationId(UUID orgId);
    Optional<Target> findByOrganizationIdAndHostAndPort(UUID orgId, String host, Integer port);
    List<Target> findByOrganizationIdAndIsPrivate(UUID orgId, boolean isPrivate);
}
