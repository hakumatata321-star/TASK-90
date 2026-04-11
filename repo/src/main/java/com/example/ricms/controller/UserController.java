package com.example.ricms.controller;

import com.example.ricms.dto.request.CreateUserRequest;
import com.example.ricms.dto.request.UserRolesRequest;
import com.example.ricms.dto.response.PageResponse;
import com.example.ricms.dto.response.UserResponse;
import com.example.ricms.security.SecurityUtils;
import com.example.ricms.service.UserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/v1/users")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

    /** GET /v1/users — requires USER:READ */
    @GetMapping
    public ResponseEntity<PageResponse<UserResponse>> listUsers(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int pageSize,
            @RequestParam(required = false) String q) {
        return ResponseEntity.ok(userService.listUsers(page, pageSize, q));
    }

    /** GET /v1/users/{userId} — requires USER:READ */
    @GetMapping("/{userId}")
    public ResponseEntity<UserResponse> getUser(@PathVariable UUID userId) {
        return ResponseEntity.ok(userService.getUser(userId));
    }

    /**
     * POST /v1/users — admin user creation, requires USER:WRITE.
     * Password is bcrypt-hashed by UserService before persistence.
     */
    @PostMapping
    public ResponseEntity<UserResponse> createUser(@Valid @RequestBody CreateUserRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(userService.createUser(request, SecurityUtils.getCurrentUserId()));
    }

    /**
     * POST /v1/users/{userId}/roles — requires RBAC_USER_ROLES:WRITE.
     * Emits a PERMISSION_CHANGE audit event with before/after role diff (Q1/Q2).
     */
    @PostMapping("/{userId}/roles")
    public ResponseEntity<UserResponse> assignRoles(
            @PathVariable UUID userId,
            @Valid @RequestBody UserRolesRequest request) {
        return ResponseEntity.ok(
                userService.assignRoles(userId, request.getRoleIds(), SecurityUtils.getCurrentUserId()));
    }
}
