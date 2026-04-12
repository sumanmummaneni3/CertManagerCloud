package com.codecatalyst.repository;
import com.codecatalyst.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
@Repository
public interface UserRepository extends JpaRepository<User, UUID> {
    Optional<User> findByEmail(String email);
    Optional<User> findByGoogleSub(String googleSub);
    List<User> findAllByOrganizationId(UUID orgId);
    boolean existsByEmailAndOrganizationId(String email, UUID orgId);
    Optional<User> findByIdAndOrganizationId(UUID id, UUID orgId);
}
