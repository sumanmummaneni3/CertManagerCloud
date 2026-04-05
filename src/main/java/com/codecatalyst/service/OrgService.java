package com.codecatalyst.service;

import com.codecatalyst.dto.response.OrgResponse;
import com.codecatalyst.entity.Organization;
import com.codecatalyst.entity.Subscription;
import com.codecatalyst.exception.ResourceNotFoundException;
import com.codecatalyst.repository.OrganizationRepository;
import com.codecatalyst.repository.SubscriptionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class OrgService {

    private final OrganizationRepository orgRepository;
    private final SubscriptionRepository subscriptionRepository;

    public OrgResponse getOrg(UUID orgId) {
        Organization org = orgRepository.findById(orgId)
                .orElseThrow(() -> new ResourceNotFoundException("Organization not found"));
        Subscription sub = subscriptionRepository.findByOrganizationId(orgId).orElse(null);
        return toResponse(org, sub);
    }

    @Transactional
    public OrgResponse updateName(UUID orgId, String name) {
        Organization org = orgRepository.findById(orgId)
                .orElseThrow(() -> new ResourceNotFoundException("Organization not found"));
        org.setName(name);
        orgRepository.save(org);
        Subscription sub = subscriptionRepository.findByOrganizationId(orgId).orElse(null);
        return toResponse(org, sub);
    }

    private OrgResponse toResponse(Organization org, Subscription sub) {
        return OrgResponse.builder()
                .id(org.getId())
                .name(org.getName())
                .slug(org.getSlug())
                .maxTargets(sub != null ? sub.getMaxTargets() : 10)
                .status(sub != null ? sub.getStatus() : null)
                .createdAt(org.getCreatedAt())
                .build();
    }
}
