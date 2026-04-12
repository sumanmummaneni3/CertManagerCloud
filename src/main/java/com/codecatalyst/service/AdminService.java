package com.codecatalyst.service;

import com.codecatalyst.dto.request.UpdateQuotaRequest;
import com.codecatalyst.dto.response.SubscriptionResponse;
import com.codecatalyst.entity.Subscription;
import com.codecatalyst.exception.ResourceNotFoundException;
import com.codecatalyst.repository.OrganizationRepository;
import com.codecatalyst.repository.SubscriptionRepository;
import com.codecatalyst.repository.TargetRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AdminService {

    private final OrganizationRepository orgRepository;
    private final SubscriptionRepository subscriptionRepository;
    private final TargetRepository targetRepository;

    public List<SubscriptionResponse> listAllOrgs() {
        return subscriptionRepository.findAll().stream()
                .map(this::toResponse)
                .toList();
    }

    public SubscriptionResponse getOrgSubscription(UUID orgId) {
        Subscription sub = subscriptionRepository.findByOrganizationId(orgId)
                .orElseThrow(() -> new ResourceNotFoundException("No subscription found for org: " + orgId));
        return toResponse(sub);
    }

    @Transactional
    public SubscriptionResponse updateQuota(UUID orgId, UpdateQuotaRequest request) {
        // Verify org exists
        if (!orgRepository.existsById(orgId)) {
            throw new ResourceNotFoundException("Organization not found: " + orgId);
        }
        Subscription sub = subscriptionRepository.findByOrganizationId(orgId)
                .orElseThrow(() -> new ResourceNotFoundException("No subscription found for org: " + orgId));

        int oldLimit = sub.getMaxCertificateQuota();
        sub.setMaxCertificateQuota(request.getMaxTargets());
        sub = subscriptionRepository.save(sub);

        log.info("SUPER_ADMIN updated quota for org {} from {} to {}",
                orgId, oldLimit, request.getMaxTargets());
        return toResponse(sub);
    }

    private SubscriptionResponse toResponse(Subscription sub) {
        long current = targetRepository.countByOrganizationId(sub.getOrganization().getId());
        return SubscriptionResponse.builder()
                .id(sub.getId())
                .orgId(sub.getOrganization().getId())
                .orgName(sub.getOrganization().getName())
                .maxTargets(sub.getMaxCertificateQuota())
                .currentTargets(current)
                .status(sub.getStatus())
                .createdAt(sub.getCreatedAt())
                .updatedAt(sub.getUpdatedAt())
                .build();
    }
}
