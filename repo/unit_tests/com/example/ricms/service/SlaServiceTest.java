package com.example.ricms.service;

import com.example.ricms.domain.entity.OperationalParam;
import com.example.ricms.repository.OperationalParamRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.DayOfWeek;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for SlaService covering:
 *  - first-response deadline within business hours (Q10)
 *  - first-response deadline outside business hours / across weekends
 *  - resolution deadline across business days (Q10)
 *  - holiday exclusion from SLA calculations
 *  - edge cases at business-day boundaries
 *  - configurable business hours
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SlaServiceTest {

    @Mock OperationalParamRepository operationalParamRepository;

    SlaService service;

    @BeforeEach
    void setUp() {
        service = new SlaService(operationalParamRepository);

        // Defaults: 4-hour first response, 3-day resolution, Mon–Fri 09:00–17:00, no holidays
        stubParam("sla_first_response_hours", "4");
        stubParam("sla_resolution_days", "3");
        stubParam("business_hours_start", "\"09:00\"");
        stubParam("business_hours_end", "\"17:00\"");
        stubParam("business_days", "[1,2,3,4,5]");
        stubParam("business_holidays", "[]");
    }

    // ── First response: within business hours ─────────────────────────────────

    @Test
    void firstResponse_withinBusinessHours_adds4Hours() {
        // Monday 10:00 + 4 business hours = Monday 14:00
        OffsetDateTime monday10am = OffsetDateTime.of(2026, 4, 6, 10, 0, 0, 0, ZoneOffset.UTC);

        OffsetDateTime due = service.computeFirstResponseDue(monday10am);

        assertThat(due.getHour()).isEqualTo(14);
        assertThat(due.getDayOfWeek()).isEqualTo(DayOfWeek.MONDAY);
    }

    @Test
    void firstResponse_lateInDay_rollsToNextBusinessDay() {
        // Monday 15:00 + 4 hours = only 2 hours left Monday (15→17), then 2 hours Tuesday (09→11)
        OffsetDateTime monday3pm = OffsetDateTime.of(2026, 4, 6, 15, 0, 0, 0, ZoneOffset.UTC);

        OffsetDateTime due = service.computeFirstResponseDue(monday3pm);

        assertThat(due.getDayOfWeek()).isEqualTo(DayOfWeek.TUESDAY);
        assertThat(due.getHour()).isEqualTo(11);
    }

    @Test
    void firstResponse_afterBusinessHours_startsNextMorning() {
        // Monday 18:00 (after close) + 4 hours = Tuesday 13:00
        OffsetDateTime monday6pm = OffsetDateTime.of(2026, 4, 6, 18, 0, 0, 0, ZoneOffset.UTC);

        OffsetDateTime due = service.computeFirstResponseDue(monday6pm);

        assertThat(due.getDayOfWeek()).isEqualTo(DayOfWeek.TUESDAY);
        assertThat(due.getHour()).isEqualTo(13);
    }

    @Test
    void firstResponse_beforeBusinessHours_snapsToStart() {
        // Monday 07:00 (before open) → snaps to Monday 09:00 + 4 hours = Monday 13:00
        OffsetDateTime monday7am = OffsetDateTime.of(2026, 4, 6, 7, 0, 0, 0, ZoneOffset.UTC);

        OffsetDateTime due = service.computeFirstResponseDue(monday7am);

        assertThat(due.getDayOfWeek()).isEqualTo(DayOfWeek.MONDAY);
        assertThat(due.getHour()).isEqualTo(13);
    }

    // ── First response: across weekends ───────────────────────────────────────

    @Test
    void firstResponse_fridayAfternoon_rollsToMonday() {
        // Friday 16:00 + 4 hours = 1 hour left Friday (16→17), skips Sat/Sun, 3 hours Monday (09→12)
        OffsetDateTime friday4pm = OffsetDateTime.of(2026, 4, 10, 16, 0, 0, 0, ZoneOffset.UTC);

        OffsetDateTime due = service.computeFirstResponseDue(friday4pm);

        assertThat(due.getDayOfWeek()).isEqualTo(DayOfWeek.MONDAY);
        assertThat(due.getHour()).isEqualTo(12);
    }

    @Test
    void firstResponse_saturday_rollsToMonday() {
        // Saturday 12:00 → next business day is Monday 09:00 + 4 hours = Monday 13:00
        OffsetDateTime saturday = OffsetDateTime.of(2026, 4, 11, 12, 0, 0, 0, ZoneOffset.UTC);

        OffsetDateTime due = service.computeFirstResponseDue(saturday);

        assertThat(due.getDayOfWeek()).isEqualTo(DayOfWeek.MONDAY);
        assertThat(due.getHour()).isEqualTo(13);
    }

    // ── First response: with holidays ─────────────────────────────────────────

    @Test
    void firstResponse_onHoliday_skipsToNextBusinessDay() {
        // Monday 2026-04-06 is marked as holiday
        stubParam("business_holidays", "[\"2026-04-06\"]");

        // Monday 10:00 → skip Monday (holiday), Tuesday 09:00 + 4 hours = Tuesday 13:00
        OffsetDateTime monday10am = OffsetDateTime.of(2026, 4, 6, 10, 0, 0, 0, ZoneOffset.UTC);

        OffsetDateTime due = service.computeFirstResponseDue(monday10am);

        assertThat(due.getDayOfWeek()).isEqualTo(DayOfWeek.TUESDAY);
        assertThat(due.getHour()).isEqualTo(13);
    }

    // ── Resolution: business days ─────────────────────────────────────────────

    @Test
    void resolution_withinWeek_adds3BusinessDays() {
        // Monday → +3 business days = Thursday at close (17:00)
        OffsetDateTime monday = OffsetDateTime.of(2026, 4, 6, 10, 0, 0, 0, ZoneOffset.UTC);

        OffsetDateTime due = service.computeResolutionDue(monday);

        assertThat(due.getDayOfWeek()).isEqualTo(DayOfWeek.THURSDAY);
        assertThat(due.getHour()).isEqualTo(17);
    }

    @Test
    void resolution_acrossWeekend_skipsWeekend() {
        // Thursday → +3 business days: Fri, (skip Sat/Sun), Mon, Tue = Tuesday at 17:00
        OffsetDateTime thursday = OffsetDateTime.of(2026, 4, 9, 10, 0, 0, 0, ZoneOffset.UTC);

        OffsetDateTime due = service.computeResolutionDue(thursday);

        assertThat(due.getDayOfWeek()).isEqualTo(DayOfWeek.TUESDAY);
        assertThat(due.getHour()).isEqualTo(17);
    }

    @Test
    void resolution_withHoliday_skipsHoliday() {
        // Monday 2026-04-07 (Tuesday) is holiday
        stubParam("business_holidays", "[\"2026-04-07\"]");

        // Monday → +3 business days: Tue is holiday → Wed, Thu, Fri = Friday at 17:00
        OffsetDateTime monday = OffsetDateTime.of(2026, 4, 6, 10, 0, 0, 0, ZoneOffset.UTC);

        OffsetDateTime due = service.computeResolutionDue(monday);

        assertThat(due.getDayOfWeek()).isEqualTo(DayOfWeek.FRIDAY);
        assertThat(due.getHour()).isEqualTo(17);
    }

    @Test
    void resolution_friday_acrossWeekend() {
        // Friday → +3 business days: Mon, Tue, Wed = Wednesday at 17:00
        OffsetDateTime friday = OffsetDateTime.of(2026, 4, 10, 14, 0, 0, 0, ZoneOffset.UTC);

        OffsetDateTime due = service.computeResolutionDue(friday);

        assertThat(due.getDayOfWeek()).isEqualTo(DayOfWeek.WEDNESDAY);
        assertThat(due.getHour()).isEqualTo(17);
    }

    // ── Configurable SLA parameters ───────────────────────────────────────────

    @Test
    void firstResponse_customHours_respected() {
        stubParam("sla_first_response_hours", "2");

        OffsetDateTime monday10am = OffsetDateTime.of(2026, 4, 6, 10, 0, 0, 0, ZoneOffset.UTC);

        OffsetDateTime due = service.computeFirstResponseDue(monday10am);

        assertThat(due.getHour()).isEqualTo(12);
    }

    @Test
    void resolution_customDays_respected() {
        stubParam("sla_resolution_days", "5");

        // Monday + 5 business days = next Monday at 17:00
        OffsetDateTime monday = OffsetDateTime.of(2026, 4, 6, 10, 0, 0, 0, ZoneOffset.UTC);

        OffsetDateTime due = service.computeResolutionDue(monday);

        assertThat(due.getDayOfWeek()).isEqualTo(DayOfWeek.MONDAY);
        assertThat(due.getHour()).isEqualTo(17);
    }

    // ── Edge cases ────────────────────────────────────────────────────────────

    @Test
    void firstResponse_exactlyAtBusinessEnd_rollsToNextDay() {
        // Monday 17:00 (exactly at close) → Tuesday 09:00 + 4 hours = Tuesday 13:00
        OffsetDateTime mondayClose = OffsetDateTime.of(2026, 4, 6, 17, 0, 0, 0, ZoneOffset.UTC);

        OffsetDateTime due = service.computeFirstResponseDue(mondayClose);

        assertThat(due.getDayOfWeek()).isEqualTo(DayOfWeek.TUESDAY);
        assertThat(due.getHour()).isEqualTo(13);
    }

    @Test
    void firstResponse_exactlyAtBusinessStart_adds4Hours() {
        // Monday 09:00 + 4 hours = Monday 13:00
        OffsetDateTime mondayOpen = OffsetDateTime.of(2026, 4, 6, 9, 0, 0, 0, ZoneOffset.UTC);

        OffsetDateTime due = service.computeFirstResponseDue(mondayOpen);

        assertThat(due.getDayOfWeek()).isEqualTo(DayOfWeek.MONDAY);
        assertThat(due.getHour()).isEqualTo(13);
    }

    @Test
    void firstResponse_missingParams_usesDefaults() {
        // Clear all param stubs — service should use fallback defaults
        when(operationalParamRepository.findById(any())).thenReturn(Optional.empty());

        OffsetDateTime monday10am = OffsetDateTime.of(2026, 4, 6, 10, 0, 0, 0, ZoneOffset.UTC);

        // Default: 4 hours → Monday 14:00
        OffsetDateTime due = service.computeFirstResponseDue(monday10am);

        assertThat(due.getHour()).isEqualTo(14);
    }

    @Test
    void resolution_multipleConsecutiveHolidays() {
        // Mon, Tue, Wed are holidays
        stubParam("business_holidays", "[\"2026-04-06\",\"2026-04-07\",\"2026-04-08\"]");

        // Sunday night → skip Mon/Tue/Wed holidays, Thu is first business day
        // +3 business days from Sun: Thu, Fri, (skip Sat/Sun), Mon = Monday at 17:00
        OffsetDateTime sunday = OffsetDateTime.of(2026, 4, 5, 20, 0, 0, 0, ZoneOffset.UTC);

        OffsetDateTime due = service.computeResolutionDue(sunday);

        assertThat(due.getDayOfMonth()).isEqualTo(13); // Monday April 13
        assertThat(due.getHour()).isEqualTo(17);
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private void stubParam(String key, String value) {
        OperationalParam p = new OperationalParam();
        p.setKey(key);
        p.setValueJson(value);
        when(operationalParamRepository.findById(key)).thenReturn(Optional.of(p));
    }
}
