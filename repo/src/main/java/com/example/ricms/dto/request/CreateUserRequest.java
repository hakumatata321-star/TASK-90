package com.example.ricms.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;
import java.util.UUID;

@Data
public class CreateUserRequest {

    @NotBlank
    @Size(min = 3, max = 100)
    private String username;

    @NotBlank
    @Size(min = 8, max = 100)
    private String password;

    /** Optional E.164 phone number, e.g. +12125550123. */
    @Pattern(regexp = "^\\+?[1-9]\\d{6,14}$", message = "phone must be a valid E.164 number")
    private String phone;

    /** Optional: role IDs to assign immediately on creation. */
    private List<UUID> roleIds;
}
