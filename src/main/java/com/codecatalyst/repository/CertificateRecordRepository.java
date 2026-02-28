package com.codecatalyst.repository;

import com.codecatalyst.entity.CertificateRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Repository
public interface CertificateRecordRepository extends JpaRepository<CertificateRecord, UUID> {
    List<CertificateRecord> findByOrganizationId(UUID orgId);
    List<CertificateRecord> findByTargetId(UUID targetId);
    List<CertificateRecord> findByOrganizationIdAndStatus(UUID orgId, String status);

    @Query("SELECT c FROM CertificateRecord c WHERE c.organization.id = :orgId AND c.expiryDate <= :before")
    List<CertificateRecord> findExpiringBefore(@Param("orgId") UUID orgId, @Param("before") LocalDate before);

    @Query("SELECT c FROM CertificateRecord c WHERE c.expiryDate <= :before")
    List<CertificateRecord> findAllExpiringBefore(@Param("before") LocalDate before);
}
