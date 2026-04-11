package com.example.ricms.service;

import com.example.ricms.config.AppProperties;
import com.example.ricms.domain.entity.User;
import com.example.ricms.domain.enums.UserStatus;
import com.example.ricms.dto.request.LoginRequest;
import com.example.ricms.dto.request.PasswordRotateRequest;
import com.example.ricms.dto.response.AuthResponse;
import com.example.ricms.exception.AppException;
import com.example.ricms.repository.UserRepository;
import com.example.ricms.security.JwtUtil;
import com.example.ricms.security.PermissionEnforcer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;
    private final AppProperties appProperties;
    private final AuditService auditService;
    private final PermissionEnforcer permissionEnforcer;

    /**
     * Authenticate with local username + password and issue a JWT (Q1).
     * Emits an audit event on every successful login.
     */
    public AuthResponse login(LoginRequest request) {
        User user = userRepository.findByUsername(request.getUsername())
                .orElseThrow(() -> new AppException(
                        "INVALID_CREDENTIALS", "Invalid username or password", HttpStatus.UNAUTHORIZED));

        if (!passwordEncoder.matches(request.getPassword(), user.getPasswordHash())) {
            throw new AppException(
                    "INVALID_CREDENTIALS", "Invalid username or password", HttpStatus.UNAUTHORIZED);
        }

        if (user.getStatus() == UserStatus.SUSPENDED || user.getStatus() == UserStatus.DEACTIVATED) {
            throw new AppException("ACCOUNT_DISABLED", "Account is not active", HttpStatus.FORBIDDEN);
        }

        String token = jwtUtil.generateToken(user.getId(), user.getUsername());
        long expiresIn = appProperties.getJwt().getExpirationMs() / 1000;

        // Audit every successful login (no diff; before/after both null)
        auditService.record(user.getId(), "USER", user.getId().toString(),
                "LOGIN", "USER_REQUEST", null, null);

        return new AuthResponse(token, expiresIn);
    }

    /**
     * Self-service password rotation (Q1 – AUTH_USER_SELF:WRITE).
     *
     * Ownership check: the caller must be the same user OR hold USER:WRITE
     * (admin override). The diff stored in the audit event masks both hash
     * values as [REDACTED] so no credential material enters the audit log (Q2).
     */
    @Transactional
    public void rotatePassword(UUID callerId, UUID targetUserId, PasswordRotateRequest request) {
        // Permission: self-service write on own account
        permissionEnforcer.require("AUTH_USER_SELF", "WRITE");
        // Ownership row-level check: must be own account unless admin (Q1)
        permissionEnforcer.requireSelf(targetUserId);

        User user = userRepository.findById(targetUserId)
                .orElseThrow(() -> new AppException("USER_NOT_FOUND", "User not found", HttpStatus.NOT_FOUND));

        if (!passwordEncoder.matches(request.getCurrentPassword(), user.getPasswordHash())) {
            throw new AppException("INVALID_CREDENTIALS", "Current password is incorrect", HttpStatus.UNAUTHORIZED);
        }

        String oldHash = user.getPasswordHash();   // will be masked in diff
        user.setPasswordHash(passwordEncoder.encode(request.getNewPassword()));
        userRepository.save(user);

        // Diff: passwordHash field – both values are masked by AuditService
        auditService.record(callerId, "USER", targetUserId.toString(),
                "PASSWORD_ROTATE", "USER_REQUEST",
                java.util.Map.of("passwordHash", oldHash),
                java.util.Map.of("passwordHash", user.getPasswordHash()));
    }
}
