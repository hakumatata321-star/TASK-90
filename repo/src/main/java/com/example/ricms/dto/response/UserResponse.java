package com.example.ricms.dto.response;

import com.example.ricms.domain.enums.UserStatus;
import lombok.Builder;
import lombok.Data;

import java.time.OffsetDateTime;
import java.util.Set;
import java.util.UUID;

@Data
@Builder
public class UserResponse {
    private UUID id;
    private String username;
    private UserStatus status;
    private Set<String> roles;
    /** Masked phone number (e.g. ****1234), present only if a phone was stored. */
    private String phoneMasked;
    private OffsetDateTime createdAt;
}
