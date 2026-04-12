package com.codecatalyst.repository;

import com.codecatalyst.entity.Organization;
import com.codecatalyst.enums.OrgType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface OrganizationRepository extends JpaRepository<Organization, UUID> {
    List<Organization> findAllByParentOrgId(UUID parentOrgId);
    List<Organization> findAllByOrgType(OrgType orgType);
}
