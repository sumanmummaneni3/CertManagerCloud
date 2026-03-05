package com.codecatalyst.repository;

import com.codecatalyst.entity.User;
import com.codecatalyst.enums.UserRole;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface UserRepository extends JpaRepository<User, UUID> {
    Optional<User> findByGoogleSub(String googleSub);
    Optional<User> findByEmail(String email);
    boolean existsByEmail(String email);
    List<User> findByOrganizationId(UUID orgId);
    List<User> findByOrganizationIdAndRole(UUID orgId, UserRole role);
}
