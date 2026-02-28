package com.codecatalyst.service;

import com.codecatalyst.entity.Organization;
import com.codecatalyst.entity.Target;
import com.codecatalyst.exceptions.ResourceNotFoundException;
import com.codecatalyst.repository.TargetRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class TargetService {

    private final TargetRepository repository;
    private final OrganizationService organizationService;

    public List<Target> findByOrg(UUID orgId) {
        return repository.findByOrganizationId(orgId);
    }

    public Target findById(UUID id) {
        return repository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Target not found: " + id));
    }

    @Transactional
    public Target create(UUID orgId, Target target) {
        Organization org = organizationService.findById(orgId);
        repository.findByOrganizationIdAndHostAndPort(orgId, target.getHost(), target.getPort())
                .ifPresent(t -> { throw new IllegalArgumentException(
                        "Target already exists for host:port " + target.getHost() + ":" + target.getPort()); });
        target.setOrganization(org);
        return repository.save(target);
    }

    @Transactional
    public Target update(UUID id, Target updated) {
        Target existing = findById(id);
        existing.setHost(updated.getHost());
        existing.setPort(updated.getPort());
        existing.setPrivate(updated.isPrivate());
        return repository.save(existing);
    }

    @Transactional
    public void delete(UUID id) {
        Target target = findById(id);
        repository.delete(target);
    }
}
