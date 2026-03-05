package com.codecatalyst.security;

import com.codecatalyst.entity.User;
import com.codecatalyst.exception.ResourceNotFoundException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * Central enforcement point for multi-tenant isolation.
 *
 * Every controller and service that accesses org-scoped data calls
 * currentUser() to get the authenticated user, then passes
 * user.getOrganization().getId() as the org_id filter on every query.
 *
 * This ensures it is architecturally impossible for a request to read
 * or modify data belonging to a different organisation — even if a bug
 * were to pass the wrong orgId in a path variable.
 */
@Service
public class OrgSecurityService {

    /**
     * Returns the authenticated User from the SecurityContext.
     * Throws if no authentication is present (should never happen after
     * the JWT filter runs and Spring Security enforces authentication).
     */
    public User currentUser() {
        Object principal = SecurityContextHolder.getContext()
                .getAuthentication().getPrincipal();
        if (!(principal instanceof User user)) {
            throw new IllegalStateException("No authenticated user in SecurityContext");
        }
        return user;
    }

    /**
     * Returns the authenticated user's organisation ID.
     * Use this as the org_id parameter on every repository query.
     */
    public UUID currentOrgId() {
        return currentUser().getOrganization().getId();
    }

    /**
     * Asserts that the path-variable orgId matches the authenticated user's org.
     * Call this at the top of any controller method that accepts {orgId} in the path.
     *
     * This is a defence-in-depth guard — the JWT filter and query scoping already
     * enforce isolation, but this makes the intent explicit and catches mistakes early.
     */
    public void assertOrgAccess(UUID orgId) {
        if (!currentOrgId().equals(orgId)) {
            // Return 404 rather than 403 — do not reveal that the resource exists
            throw new ResourceNotFoundException("Organization not found: " + orgId);
        }
    }
}
