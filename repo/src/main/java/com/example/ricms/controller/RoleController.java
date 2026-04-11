package com.example.ricms.controller;

import com.example.ricms.dto.request.RoleCreateRequest;
import com.example.ricms.dto.request.RolePermissionsRequest;
import com.example.ricms.dto.response.PageResponse;
import com.example.ricms.dto.response.PermissionResponse;
import com.example.ricms.dto.response.RoleResponse;
import com.example.ricms.security.SecurityUtils;
import com.example.ricms.service.RoleService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequiredArgsConstructor
public class RoleController {

    private final RoleService roleService;

    // ------------------------------------------------------------------
    // Roles
    // ------------------------------------------------------------------

    @GetMapping("/v1/roles")
    public ResponseEntity<PageResponse<RoleResponse>> listRoles(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int pageSize) {
        return ResponseEntity.ok(roleService.listRoles(page, pageSize));
    }

    @GetMapping("/v1/roles/{roleId}")
    public ResponseEntity<RoleResponse> getRole(@PathVariable UUID roleId) {
        return ResponseEntity.ok(roleService.getRole(roleId));
    }

    @PostMapping("/v1/roles")
    public ResponseEntity<RoleResponse> createRole(@Valid @RequestBody RoleCreateRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(roleService.createRole(request, SecurityUtils.getCurrentUserId()));
    }

    // ------------------------------------------------------------------
    // Permissions catalogue
    // ------------------------------------------------------------------

    @GetMapping("/v1/permissions")
    public ResponseEntity<List<PermissionResponse>> listPermissions() {
        return ResponseEntity.ok(roleService.listAllPermissions());
    }

    // ------------------------------------------------------------------
    // Role ↔ Permission assignment
    // ------------------------------------------------------------------

    /**
     * POST /v1/roles/{roleId}/permissions
     *
     * Replaces the full permission set for the role.
     * Emits a PERMISSION_CHANGE audit event with before/after diff (Q2).
     * Requires RBAC_USER_ROLES:WRITE.
     */
    @PostMapping("/v1/roles/{roleId}/permissions")
    public ResponseEntity<RoleResponse> setPermissions(
            @PathVariable UUID roleId,
            @Valid @RequestBody RolePermissionsRequest request) {
        return ResponseEntity.ok(
                roleService.setPermissions(roleId, request.getPermissionIds(),
                        SecurityUtils.getCurrentUserId()));
    }
}
