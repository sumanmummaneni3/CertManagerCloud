package com.codecatalyst.repository;
import com.codecatalyst.entity.Agent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
@Repository
public interface AgentRepository extends JpaRepository<Agent, UUID> {
    List<Agent> findAllByOrganizationId(UUID orgId);
    Optional<Agent> findByIdAndOrganizationId(UUID id, UUID orgId);
}
