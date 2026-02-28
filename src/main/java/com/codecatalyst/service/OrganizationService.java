package com.codecatalyst.service;

import com.codecatalyst.entity.Organization;
import com.codecatalyst.exceptions.ResourceNotFoundException;
import com.codecatalyst.repository.OrganizationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class OrganizationService {

    private final OrganizationRepository repository;

    public List<Organization> findAll() {
        return repository.findAll();
    }

    public Organization findById(UUID id) {
        return repository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Organization not found: " + id));
    }

    @Transactional
    public Organization create(Organization org) {
        if (repository.existsByName(org.getName())) {
            throw new IllegalArgumentException("Organization name already exists: " + org.getName());
        }
        return repository.save(org);
    }

    @Transactional
    public Organization update(UUID id, Organization updated) {
        Organization existing = findById(id);
        existing.setName(updated.getName());
        existing.setKeystoreLocation(updated.getKeystoreLocation());
        existing.setApiKey(updated.getApiKey());
        return repository.save(existing);
    }

    @Transactional
    public void delete(UUID id) {
        Organization org = findById(id);
        repository.delete(org);
    }
}
