package com.example.ricms.dto.request;

import com.example.ricms.domain.enums.MemberStatus;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class MemberStatusRequest {

    @NotNull
    private MemberStatus status;
}
