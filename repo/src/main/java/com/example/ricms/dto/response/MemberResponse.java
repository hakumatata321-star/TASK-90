package com.example.ricms.dto.response;

import com.example.ricms.domain.enums.MemberStatus;
import com.example.ricms.domain.enums.MemberTier;
import lombok.Builder;
import lombok.Data;

import java.util.UUID;

@Data
@Builder
public class MemberResponse {
    private UUID memberId;
    private UUID userId;
    private MemberStatus status;
    private MemberTier tier;
    private Long pointsBalance;
}
