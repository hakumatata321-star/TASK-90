package com.example.ricms.dto.request;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;
import java.util.UUID;

@Data
public class UserRolesRequest {

    @NotNull
    private List<UUID> roleIds;
}
