package com.example.ricms.controller;

import com.example.ricms.dto.request.LoginRequest;
import com.example.ricms.dto.request.PasswordRotateRequest;
import com.example.ricms.dto.response.AuthResponse;
import com.example.ricms.security.SecurityUtils;
import com.example.ricms.service.AuthService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    /** POST /v1/auth/login — public endpoint, no JWT required. */
    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(@Valid @RequestBody LoginRequest request) {
        return ResponseEntity.ok(authService.login(request));
    }

    /**
     * POST /v1/auth/password/rotate — self-service password change.
     * Requires AUTH_USER_SELF:WRITE and ownership (callerId == targetUserId)
     * unless caller has USER:WRITE admin privilege (Q1).
     */
    @PostMapping("/password/rotate")
    public ResponseEntity<Void> rotatePassword(@Valid @RequestBody PasswordRotateRequest request) {
        java.util.UUID currentUserId = SecurityUtils.getCurrentUserId();
        authService.rotatePassword(currentUserId, currentUserId, request);
        return ResponseEntity.noContent().build();
    }
}
