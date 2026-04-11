package com.example.ricms.service;

import com.example.ricms.domain.entity.Role;
import com.example.ricms.domain.entity.User;
import com.example.ricms.domain.enums.UserStatus;
import com.example.ricms.dto.request.CreateUserRequest;
import com.example.ricms.dto.response.PageResponse;
import com.example.ricms.dto.response.UserResponse;
import com.example.ricms.exception.AppException;
import com.example.ricms.repository.RoleRepository;
import com.example.ricms.repository.UserRepository;
import com.example.ricms.security.PermissionEnforcer;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class UserService {

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final PasswordEncoder passwordEncoder;
    private final PermissionEnforcer permissionEnforcer;
    private final AuditService auditService;
    private final MemberService memberService;

    // ------------------------------------------------------------------
    // Reads (USER:READ required)
    // ------------------------------------------------------------------

    public PageResponse<UserResponse> listUsers(int page, int pageSize, String q) {
        permissionEnforcer.require("USER", "READ");
        Page<User> users = (q != null && !q.isBlank())
                ? userRepository.searchUsers(q, PageRequest.of(page, pageSize))
                : userRepository.findAll(PageRequest.of(page, pageSize));
        return PageResponse.of(users.map(this::toResponse));
    }

    public UserResponse getUser(UUID userId) {
        permissionEnforcer.require("USER", "READ");
        User user = findUser(userId);
        return toResponse(user);
    }

    // ------------------------------------------------------------------
    // Writes
    // ------------------------------------------------------------------

    /**
     * Admin-only user creation (USER:WRITE).
     * Hashes the password via BCrypt before persistence.
     */
    @Transactional
    public UserResponse createUser(CreateUserRequest request, UUID actorId) {
        permissionEnforcer.require("USER", "WRITE");

        if (userRepository.findByUsername(request.getUsername()).isPresent()) {
            throw new AppException("CONFLICT", "Username already taken: " + request.getUsername(), HttpStatus.CONFLICT);
        }

        Set<Role> roles = new HashSet<>();
        if (request.getRoleIds() != null && !request.getRoleIds().isEmpty()) {
            roles = new HashSet<>(roleRepository.findAllById(request.getRoleIds()));
        }

        String phoneMasked = null;
        String phoneEncrypted = null;
        if (request.getPhone() != null && !request.getPhone().isBlank()) {
            String phone = request.getPhone();
            phoneMasked = "****" + phone.substring(Math.max(0, phone.length() - 4));
            phoneEncrypted = phone;  // EncryptedStringConverter encrypts on persist
        }

        User user = User.builder()
                .username(request.getUsername())
                .passwordHash(passwordEncoder.encode(request.getPassword()))
                .status(UserStatus.ACTIVE)
                .roles(roles)
                .phoneMasked(phoneMasked)
                .phoneEncrypted(phoneEncrypted)
                .build();
        user = userRepository.save(user);

        // Automatically provision a BRONZE/ACTIVE member profile for every new user
        memberService.createForUser(user.getId());

        auditService.record(actorId, "USER", user.getId().toString(),
                "CREATE_USER", "ADMIN_ACTION", null,
                Map.of("username", user.getUsername(),
                       "roles", roles.stream().map(Role::getName).collect(Collectors.toSet())));

        return toResponse(user);
    }

    /**
     * Replace a user's full role set (RBAC_USER_ROLES:WRITE).
     * The before/after diff records old vs new role names for the audit trail (Q2).
     */
    @Transactional
    public UserResponse assignRoles(UUID userId, List<UUID> roleIds, UUID actorId) {
        permissionEnforcer.require("RBAC_USER_ROLES", "WRITE");

        User user = findUser(userId);

        Set<String> oldRoles = user.getRoles().stream()
                .map(Role::getName).collect(Collectors.toSet());

        Set<Role> newRoleEntities = new HashSet<>(roleRepository.findAllById(roleIds));
        if (newRoleEntities.size() != roleIds.size()) {
            throw new AppException("NOT_FOUND", "One or more role IDs not found", HttpStatus.NOT_FOUND);
        }
        user.setRoles(newRoleEntities);
        userRepository.save(user);

        Set<String> newRoles = newRoleEntities.stream()
                .map(Role::getName).collect(Collectors.toSet());

        // Diff: only the 'roles' field changed (Q2)
        auditService.record(actorId, "USER", userId.toString(),
                "ASSIGN_ROLES", "PERMISSION_CHANGE",
                Map.of("roles", oldRoles),
                Map.of("roles", newRoles));

        return toResponse(user);
    }

    // ------------------------------------------------------------------

    private User findUser(UUID userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new AppException("USER_NOT_FOUND", "User not found", HttpStatus.NOT_FOUND));
    }

    public UserResponse toResponse(User user) {
        return UserResponse.builder()
                .id(user.getId())
                .username(user.getUsername())
                .status(user.getStatus())
                .roles(user.getRoles().stream().map(Role::getName).collect(Collectors.toSet()))
                .phoneMasked(user.getPhoneMasked())
                .createdAt(user.getCreatedAt())
                .build();
    }
}
