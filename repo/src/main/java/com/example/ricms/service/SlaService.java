package com.example.ricms.service;

import com.example.ricms.repository.OperationalParamRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.Set;

/**
 * Calculates SLA deadlines respecting configurable business hours, business days
 * (Mon–Fri by default) and a holiday calendar — all driven by operational_params.
 *
 * Relevant keys (Q10):
 *   sla_first_response_hours  – integer, default 4
 *   sla_resolution_days       – integer, default 3
 *   business_hours_start      – JSON string  "09:00", default "09:00"
 *   business_hours_end        – JSON string  "17:00", default "17:00"
 *   business_days             – JSON int array [1,2,3,4,5] (ISO: 1=Mon … 7=Sun)
 *   business_holidays         – JSON string array ["2026-12-25","2027-01-01"]
 */
@Service
@RequiredArgsConstructor
public class SlaService {

    private final OperationalParamRepository operationalParamRepository;

    // Fall-back defaults used when operational_params are absent or unparseable
    private static final LocalTime        DEFAULT_START        = LocalTime.of(9, 0);
    private static final LocalTime        DEFAULT_END          = LocalTime.of(17, 0);
    private static final Set<DayOfWeek>   DEFAULT_BUSINESS_DAYS = Set.of(
            DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY,
            DayOfWeek.THURSDAY, DayOfWeek.FRIDAY);

    // ------------------------------------------------------------------
    // Public API
    // ------------------------------------------------------------------

    public OffsetDateTime computeFirstResponseDue(OffsetDateTime from) {
        int hours = getParamInt("sla_first_response_hours", 4);
        return addBusinessHours(from, hours);
    }

    public OffsetDateTime computeResolutionDue(OffsetDateTime from) {
        int days = getParamInt("sla_resolution_days", 3);
        return addBusinessDays(from, days);
    }

    // ------------------------------------------------------------------
    // Core calendar arithmetic
    // ------------------------------------------------------------------

    private OffsetDateTime addBusinessHours(OffsetDateTime from, int hoursToAdd) {
        LocalTime       start        = getBusinessStart();
        LocalTime       end          = getBusinessEnd();
        Set<DayOfWeek>  businessDays = getBusinessDays();
        Set<LocalDate>  holidays     = getHolidays();

        OffsetDateTime current = from;
        int hoursAdded = 0;

        while (hoursAdded < hoursToAdd) {
            // Skip non-business days entirely
            if (!isBusinessDay(current.toLocalDate(), businessDays, holidays)) {
                current = nextDayAt(current, start);
                continue;
            }
            // If we arrive before business start, snap forward to start
            if (current.toLocalTime().isBefore(start)) {
                current = current.withHour(start.getHour()).withMinute(start.getMinute())
                        .withSecond(0).withNano(0);
            }
            // If we're at or after business end, roll to next business day
            if (!current.toLocalTime().isBefore(end)) {
                current = nextDayAt(current, start);
                continue;
            }
            // Advance one business hour
            current = current.plusHours(1);
            hoursAdded++;
        }
        return current;
    }

    private OffsetDateTime addBusinessDays(OffsetDateTime from, int daysToAdd) {
        LocalTime      end          = getBusinessEnd();
        Set<DayOfWeek> businessDays = getBusinessDays();
        Set<LocalDate> holidays     = getHolidays();

        OffsetDateTime current = from;
        int daysAdded = 0;

        while (daysAdded < daysToAdd) {
            current = current.plusDays(1);
            if (isBusinessDay(current.toLocalDate(), businessDays, holidays)) {
                daysAdded++;
            }
        }
        return current.withHour(end.getHour()).withMinute(end.getMinute())
                .withSecond(0).withNano(0);
    }

    private boolean isBusinessDay(LocalDate date, Set<DayOfWeek> businessDays,
                                   Set<LocalDate> holidays) {
        return businessDays.contains(date.getDayOfWeek()) && !holidays.contains(date);
    }

    private OffsetDateTime nextDayAt(OffsetDateTime dt, LocalTime time) {
        return dt.plusDays(1)
                .withHour(time.getHour()).withMinute(time.getMinute())
                .withSecond(0).withNano(0);
    }

    // ------------------------------------------------------------------
    // Operational-param readers
    // ------------------------------------------------------------------

    private LocalTime getBusinessStart() {
        return getParamTime("business_hours_start", DEFAULT_START);
    }

    private LocalTime getBusinessEnd() {
        return getParamTime("business_hours_end", DEFAULT_END);
    }

    /**
     * Reads business_days as a JSON int array where each integer is an ISO day-of-week
     * value (1 = Monday … 7 = Sunday).  Falls back to Mon–Fri on any error.
     */
    private Set<DayOfWeek> getBusinessDays() {
        try {
            String raw = operationalParamRepository.findById("business_days")
                    .map(p -> p.getValueJson()).orElse(null);
            if (raw == null) return DEFAULT_BUSINESS_DAYS;
            String cleaned = raw.replaceAll("[\\[\\]\\s]", "");
            Set<DayOfWeek> days = new HashSet<>();
            for (String token : cleaned.split(",")) {
                if (!token.isBlank()) {
                    days.add(DayOfWeek.of(Integer.parseInt(token.trim())));
                }
            }
            return days.isEmpty() ? DEFAULT_BUSINESS_DAYS : days;
        } catch (Exception e) {
            return DEFAULT_BUSINESS_DAYS;
        }
    }

    /**
     * Reads business_holidays as a JSON string array of ISO-8601 date strings
     * (e.g. ["2026-12-25","2027-01-01"]).  Returns an empty set on any error.
     */
    private Set<LocalDate> getHolidays() {
        try {
            String raw = operationalParamRepository.findById("business_holidays")
                    .map(p -> p.getValueJson()).orElse(null);
            if (raw == null || raw.isBlank() || raw.equals("[]")) return Set.of();
            String cleaned = raw.replaceAll("[\\[\\]\"\\s]", "");
            Set<LocalDate> holidays = new HashSet<>();
            for (String token : cleaned.split(",")) {
                if (!token.isBlank()) {
                    holidays.add(LocalDate.parse(token.trim()));
                }
            }
            return holidays;
        } catch (Exception e) {
            return Set.of();
        }
    }

    // ------------------------------------------------------------------
    // Generic parsers
    // ------------------------------------------------------------------

    private int getParamInt(String key, int defaultVal) {
        try {
            return operationalParamRepository.findById(key)
                    .map(p -> Integer.parseInt(p.getValueJson().replaceAll("\"", "").trim()))
                    .orElse(defaultVal);
        } catch (Exception e) {
            return defaultVal;
        }
    }

    private LocalTime getParamTime(String key, LocalTime defaultVal) {
        try {
            String raw = operationalParamRepository.findById(key)
                    .map(p -> p.getValueJson().replaceAll("\"", "").trim())
                    .orElse(null);
            if (raw == null) return defaultVal;
            return LocalTime.parse(raw);
        } catch (Exception e) {
            return defaultVal;
        }
    }
}
