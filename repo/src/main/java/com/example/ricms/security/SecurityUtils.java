package com.example.ricms.security;

import com.example.ricms.exception.AppException;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.UUID;

public final class SecurityUtils {

    private SecurityUtils() {}

    public static UUID getCurrentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            throw new AppException("UNAUTHORIZED", "No authenticated user", HttpStatus.UNAUTHORIZED);
        }
        Object principal = auth.getPrincipal();
        if (principal instanceof RicmsPrincipal ricmsPrincipal) {
            return ricmsPrincipal.getUserId();
        }
        throw new AppException("UNAUTHORIZED", "Cannot determine current user", HttpStatus.UNAUTHORIZED);
    }

    public static String getCurrentUsername() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            throw new AppException("UNAUTHORIZED", "No authenticated user", HttpStatus.UNAUTHORIZED);
        }
        return auth.getName();
    }

    /** Convenience: does the current principal hold ROLE_<role>? */
    public static boolean hasRole(String role) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null) return false;
        return auth.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("ROLE_" + role));
    }

    /** Convenience: does the current principal hold PERM_<resourceType>:<operation>? */
    public static boolean hasPermission(String resourceType, String operation) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) return false;
        String authority = "PERM_" + resourceType + ":" + operation;
        return auth.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals(authority));
    }
}
