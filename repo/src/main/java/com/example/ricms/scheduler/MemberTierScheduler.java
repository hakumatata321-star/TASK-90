package com.example.ricms.scheduler;

import com.example.ricms.service.MemberService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class MemberTierScheduler {

    private final MemberService memberService;

    @Scheduled(cron = "0 0 0 1 * *")
    public void runMonthlyTierDowngrade() {
        log.info("Running monthly member tier recalculation...");
        memberService.monthlyTierDowngrade();
    }
}
