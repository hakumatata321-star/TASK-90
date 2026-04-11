package com.example.ricms.controller;

import com.example.ricms.dto.request.MemberStatusRequest;
import com.example.ricms.dto.response.MemberResponse;
import com.example.ricms.dto.response.PageResponse;
import com.example.ricms.dto.response.PointsLedgerResponse;
import com.example.ricms.security.PermissionEnforcer;
import com.example.ricms.security.SecurityUtils;
import com.example.ricms.service.MemberService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/v1/members")
@RequiredArgsConstructor
public class MemberController {

    private final MemberService memberService;
    private final PermissionEnforcer permissionEnforcer;

    // ------------------------------------------------------------------
    // Self-service: authenticated member reads their own profile/ledger
    // ------------------------------------------------------------------

    /**
     * GET /v1/members/me
     * Returns the authenticated user's membership status, tier, and balance.
     * Status ACTIVE|SUSPENDED|DELINQUENT; member pricing gates on ACTIVE (Q3).
     */
    @GetMapping("/me")
    public ResponseEntity<MemberResponse> getMyProfile() {
        return ResponseEntity.ok(
                memberService.getMyProfile(SecurityUtils.getCurrentUserId()));
    }

    /**
     * GET /v1/members/me/points/ledger
     * Append-only ledger; entries are created at payment confirmation (Q4).
     * Points = floor(totalPayable) – 1 point per whole dollar after discounts.
     */
    @GetMapping("/me/points/ledger")
    public ResponseEntity<PageResponse<PointsLedgerResponse>> getPointsLedger(
            @RequestParam(defaultValue = "0")  int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        return ResponseEntity.ok(
                memberService.getPointsLedger(SecurityUtils.getCurrentUserId(), page, pageSize));
    }

    // ------------------------------------------------------------------
    // Admin: manage any member's status (Q3)
    // ------------------------------------------------------------------

    /**
     * PUT /v1/members/{memberId}/status
     *
     * Admin endpoint to set ACTIVE / SUSPENDED / DELINQUENT (Q3).
     * Requires USER:WRITE (admin permission).
     * Changing to ACTIVE records goodStandingSince; going ACTIVE → ACTIVE is a no-op.
     */
    @PutMapping("/{memberId}/status")
    public ResponseEntity<MemberResponse> updateStatus(
            @PathVariable UUID memberId,
            @Valid @RequestBody MemberStatusRequest request) {
        permissionEnforcer.require("USER", "WRITE");
        return ResponseEntity.ok(
                memberService.updateStatus(memberId, request.getStatus(),
                        SecurityUtils.getCurrentUserId()));
    }
}
