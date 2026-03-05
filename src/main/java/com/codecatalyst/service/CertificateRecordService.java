package com.codecatalyst.service;

import com.codecatalyst.entity.CertificateRecord;
import com.codecatalyst.entity.Organization;
import com.codecatalyst.entity.Target;
import com.codecatalyst.exception.ResourceNotFoundException;
import com.codecatalyst.repository.CertificateRecordRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CertificateRecordService {

    private final CertificateRecordRepository repository;
    private final OrganizationService organizationService;
    private final TargetService targetService;

    public List<CertificateRecord> findByOrg(UUID orgId) {
        return repository.findByOrganizationId(orgId);
    }

    public List<CertificateRecord> findByTarget(UUID targetId) {
        return repository.findByTargetId(targetId);
    }

    public List<CertificateRecord> findExpiring(UUID orgId, int withinDays) {
        return repository.findExpiringBefore(orgId, LocalDate.now().plusDays(withinDays));
    }

    public CertificateRecord findById(UUID id) {
        return repository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Certificate record not found: " + id));
    }

    @Transactional
    public CertificateRecord create(UUID orgId, UUID targetId, CertificateRecord record) {
        Organization org = organizationService.findById(orgId);
        Target target = targetService.findById(targetId);
        record.setOrganization(org);
        record.setTarget(target);
        return repository.save(record);
    }

    @Transactional
    public CertificateRecord update(UUID id, CertificateRecord updated) {
        CertificateRecord existing = findById(id);
        existing.setCommonName(updated.getCommonName());
        existing.setIssuer(updated.getIssuer());
        existing.setExpiryDate(updated.getExpiryDate());
        existing.setClientOrgName(updated.getClientOrgName());
        existing.setDivisionName(updated.getDivisionName());
        existing.setStatus(updated.getStatus());
        return repository.save(existing);
    }

    @Transactional
    public void delete(UUID id) {
        repository.delete(findById(id));
    }
}
