package com.example.ricms.dto.request;

import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.util.List;
import java.util.UUID;

@Data
public class RolePermissionsRequest {

    /**
     * Full replacement set of permission IDs for the role.
     * Sending an empty list removes all permissions from the role.
     */
    @NotEmpty
    private List<UUID> permissionIds;
}
