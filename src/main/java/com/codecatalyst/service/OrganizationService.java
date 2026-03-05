package com.codecatalyst.service;

import com.codecatalyst.entity.Organization;
import com.codecatalyst.exception.ResourceNotFoundException;
import com.codecatalyst.repository.OrganizationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class OrganizationService {

    private final OrganizationRepository repository;

    public Organization findById(UUID id) {
        return repository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Organization not found: " + id));
    }

    /** Update the organisation's display name. ADMIN only. */
    @Transactional
    public Organization update(UUID id, Organization updated) {
        Organization existing = findById(id);
        if (!existing.getName().equals(updated.getName())
                && repository.existsByName(updated.getName())) {
            throw new IllegalArgumentException(
                    "Organisation name already taken: " + updated.getName());
        }
        existing.setName(updated.getName());
        return repository.save(existing);
    }
}
