package com.example.ricms.service;

import com.example.ricms.domain.entity.Permission;
import com.example.ricms.domain.entity.Role;
import com.example.ricms.dto.request.RoleCreateRequest;
import com.example.ricms.dto.response.PermissionResponse;
import com.example.ricms.dto.response.PageResponse;
import com.example.ricms.dto.response.RoleResponse;
import com.example.ricms.exception.AppException;
import com.example.ricms.repository.PermissionRepository;
import com.example.ricms.repository.RoleRepository;
import com.example.ricms.security.PermissionEnforcer;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class RoleService {

    private final RoleRepository roleRepository;
    private final PermissionRepository permissionRepository;
    private final PermissionEnforcer permissionEnforcer;
    private final AuditService auditService;

    public PageResponse<RoleResponse> listRoles(int page, int pageSize) {
        permissionEnforcer.require("USER", "READ");
        return PageResponse.of(
                roleRepository.findAll(PageRequest.of(page, pageSize)).map(this::toResponse));
    }

    public RoleResponse getRole(UUID roleId) {
        permissionEnforcer.require("USER", "READ");
        Role role = findRole(roleId);
        return toResponse(role);
    }

    public List<PermissionResponse> listAllPermissions() {
        permissionEnforcer.require("USER", "READ");
        return permissionRepository.findAll().stream()
                .map(this::toPermissionResponse)
                .collect(Collectors.toList());
    }

    @Transactional
    public RoleResponse createRole(RoleCreateRequest request, UUID actorId) {
        permissionEnforcer.require("USER", "WRITE");

        if (roleRepository.findByName(request.getName()).isPresent()) {
            throw new AppException("CONFLICT", "Role name already exists: " + request.getName(), HttpStatus.CONFLICT);
        }

        Role role = Role.builder()
                .name(request.getName().toUpperCase())
                .description(request.getDescription())
                .permissions(new HashSet<>())
                .build();
        role = roleRepository.save(role);

        auditService.record(actorId, "ROLE", role.getId().toString(),
                "CREATE_ROLE", "ADMIN_ACTION", null,
                java.util.Map.of("name", role.getName()));

        return toResponse(role);
    }

    /**
     * Replace the full permission set of a role (Q1 / Q2).
     * The diff records old vs new permission names for the audit trail.
     */
    @Transactional
    public RoleResponse setPermissions(UUID roleId, List<UUID> permissionIds, UUID actorId) {
        permissionEnforcer.require("RBAC_USER_ROLES", "WRITE");

        Role role = findRole(roleId);

        Set<String> oldPermNames = role.getPermissions().stream()
                .map(p -> p.getResourceType() + ":" + p.getOperation())
                .collect(Collectors.toSet());

        Set<Permission> newPerms = new HashSet<>(permissionRepository.findAllById(permissionIds));
        if (newPerms.size() != permissionIds.size()) {
            throw new AppException("NOT_FOUND", "One or more permission IDs not found", HttpStatus.NOT_FOUND);
        }
        role.setPermissions(newPerms);
        roleRepository.save(role);

        Set<String> newPermNames = newPerms.stream()
                .map(p -> p.getResourceType() + ":" + p.getOperation())
                .collect(Collectors.toSet());

        auditService.record(actorId, "ROLE", roleId.toString(),
                "SET_PERMISSIONS", "PERMISSION_CHANGE",
                java.util.Map.of("permissions", oldPermNames),
                java.util.Map.of("permissions", newPermNames));

        return toResponse(role);
    }

    // -------------------------------------------------------------------------

    private Role findRole(UUID roleId) {
        return roleRepository.findById(roleId)
                .orElseThrow(() -> new AppException("NOT_FOUND", "Role not found", HttpStatus.NOT_FOUND));
    }

    public RoleResponse toResponse(Role role) {
        Set<PermissionResponse> perms = role.getPermissions().stream()
                .map(this::toPermissionResponse)
                .collect(Collectors.toSet());
        return RoleResponse.builder()
                .id(role.getId())
                .name(role.getName())
                .description(role.getDescription())
                .permissions(perms)
                .createdAt(role.getCreatedAt())
                .build();
    }

    private PermissionResponse toPermissionResponse(Permission p) {
        return PermissionResponse.builder()
                .id(p.getId())
                .resourceType(p.getResourceType())
                .operation(p.getOperation())
                .build();
    }
}
