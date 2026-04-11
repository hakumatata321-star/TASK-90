package com.example.ricms.security;

import com.example.ricms.exception.AppException;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Centralised fine-grained permission enforcement (Q1).
 *
 * Authorities loaded at login time by UserDetailsServiceImpl have the form:
 *   ROLE_<name>      – e.g. ROLE_ADMIN
 *   PERM_<RT>:<OP>   – e.g. PERM_ORDER:WRITE
 *
 * Calling require() on a service write path before any mutation ensures every
 * privileged action is gated. requireSelf() adds an optional ownership check:
 * the caller must be the target user OR possess USER:WRITE (admin bypass).
 */
@Component
public class PermissionEnforcer {

    /**
     * Throw 403 if the current principal does not hold the requested permission.
     */
    public void require(String resourceType, String operation) {
        Authentication auth = currentAuth();
        String authority = "PERM_" + resourceType + ":" + operation;
        boolean granted = auth.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals(authority));
        if (!granted) {
            throw new AppException("FORBIDDEN",
                    "Missing required permission: " + resourceType + ":" + operation,
                    HttpStatus.FORBIDDEN);
        }
    }

    /**
     * Ownership row-level check: caller must be targetUserId OR hold USER:WRITE.
     * Use for self-service endpoints (e.g. password rotation).
     */
    public void requireSelf(UUID targetUserId) {
        UUID callerId = SecurityUtils.getCurrentUserId();
        if (callerId.equals(targetUserId)) {
            return; // owner – allow
        }
        // Non-owner must have USER:WRITE (admin bypass)
        Authentication auth = currentAuth();
        boolean adminBypass = auth.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("PERM_USER:WRITE"));
        if (!adminBypass) {
            throw new AppException("FORBIDDEN",
                    "You may only modify your own resource", HttpStatus.FORBIDDEN);
        }
    }

    /** Non-throwing check – use when the result gates optional behaviour. */
    public boolean hasPermission(String resourceType, String operation) {
        try {
            Authentication auth = currentAuth();
            String authority = "PERM_" + resourceType + ":" + operation;
            return auth.getAuthorities().stream()
                    .anyMatch(a -> a.getAuthority().equals(authority));
        } catch (AppException e) {
            return false;
        }
    }

    private Authentication currentAuth() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            throw new AppException("UNAUTHORIZED", "Authentication required", HttpStatus.UNAUTHORIZED);
        }
        return auth;
    }
}
