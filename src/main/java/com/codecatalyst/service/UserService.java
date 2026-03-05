package com.codecatalyst.service;

import com.codecatalyst.entity.Organization;
import com.codecatalyst.entity.User;
import com.codecatalyst.enums.UserRole;
import com.codecatalyst.exception.ResourceNotFoundException;
import com.codecatalyst.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class UserService {

    private final UserRepository repository;
    private final OrganizationService organizationService;

    public List<User> findByOrg(UUID orgId) {
        return repository.findByOrganizationId(orgId);
    }

    public List<User> findByOrgAndRole(UUID orgId, UserRole role) {
        return repository.findByOrganizationIdAndRole(orgId, role);
    }

    public User findById(UUID id) {
        return repository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("User not found: " + id));
    }

    @Transactional
    public User create(UUID orgId, User user) {
        if (repository.existsByEmail(user.getEmail())) {
            throw new IllegalArgumentException("Email already registered: " + user.getEmail());
        }
        Organization org = organizationService.findById(orgId);
        user.setOrganization(org);
        return repository.save(user);
    }

    @Transactional
    public User update(UUID id, User updated) {
        User existing = findById(id);
        existing.setEmail(updated.getEmail());
        existing.setRole(updated.getRole());
        return repository.save(existing);
    }

    @Transactional
    public void delete(UUID id) {
        User user = findById(id);
        repository.delete(user);
    }
}
