package com.pitflow.appointment;

import com.pitflow.common.ApiException;
import jakarta.validation.constraints.*;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class BookingPolicy {
  public static final int SLOT_MINUTES = 30;
  private final Clock clock;
  private final JdbcTemplate db;
  private final ZoneId zone;
  private final LocalTime opensAt;
  private final LocalTime closesAt;
  private final int horizonDays;
  private final Set<DayOfWeek> closedDays;

  public BookingPolicy(
      Clock clock,
      JdbcTemplate db,
      @Value("${pitflow.booking.timezone:Asia/Seoul}") String timezone,
      @Value("${pitflow.booking.opens-at:09:00}") String opensAt,
      @Value("${pitflow.booking.closes-at:18:00}") String closesAt,
      @Value("${pitflow.booking.horizon-days:30}") int horizonDays,
      @Value("${pitflow.booking.closed-days:SUNDAY}") Set<DayOfWeek> closedDays) {
    this.clock = clock;
    this.db = db;
    this.zone = ZoneId.of(timezone);
    this.opensAt = LocalTime.parse(opensAt);
    this.closesAt = LocalTime.parse(closesAt);
    this.horizonDays = horizonDays;
    this.closedDays = Set.copyOf(closedDays);
    if (!this.opensAt.isBefore(this.closesAt)
        || !aligned(this.opensAt)
        || !aligned(this.closesAt)
        || horizonDays < 1
        || horizonDays > 365) {
      throw new IllegalArgumentException("Invalid booking hours or horizon");
    }
  }

  public Instant now() {
    return clock.instant();
  }

  public ZoneId zone() {
    return zone;
  }

  public LocalTime opensAt() {
    return opensAt;
  }

  public LocalTime closesAt() {
    return closesAt;
  }

  public OffsetDateTime at(LocalDate date, LocalTime time) {
    return date.atTime(time).atZone(zone).toOffsetDateTime();
  }

  public boolean closed(LocalDate date) {
    var overrides = db.queryForList("SELECT closed FROM booking_day_overrides WHERE calendar_date=?", date);
    return overrides.isEmpty()
        ? configuredDays().contains(date.getDayOfWeek())
        : Boolean.TRUE.equals(overrides.get(0).get("closed"));
  }

  private Set<DayOfWeek> configuredDays() {
    String configured =
        db.queryForObject("SELECT closed_days FROM booking_calendar WHERE id=1", String.class);
    if (configured == null) return closedDays;
    if (configured.isEmpty()) return Set.of();
    var days = EnumSet.noneOf(DayOfWeek.class);
    for (String day : configured.split(",")) days.add(DayOfWeek.valueOf(day));
    return days;
  }

  public void lockCalendar() {
    db.queryForObject("SELECT id FROM booking_calendar WHERE id=1 FOR UPDATE", Integer.class);
  }

  public record CalendarRequest(
      @Min(0) long revision,
      @NotNull Set<@NotNull DayOfWeek> closedDays,
      @NotNull @Size(max = 366) Map<@NotNull LocalDate, @NotNull Boolean> overrides) {}

  @Transactional(
      readOnly = true,
      isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
  public Map<String, Object> calendar() {
    Map<String, Boolean> overrides = new TreeMap<>();
    db.queryForList("SELECT calendar_date,closed FROM booking_day_overrides ORDER BY calendar_date")
        .forEach(row -> overrides.put(row.get("calendar_date").toString(), (Boolean) row.get("closed")));
    return Map.of(
        "revision",
        db.queryForObject("SELECT revision FROM booking_calendar WHERE id=1", Long.class),
        "closedDays",
        configuredDays(),
        "overrides",
        overrides);
  }

  @Transactional
  public Map<String, Object> saveCalendar(CalendarRequest request) {
    lockCalendar();
    long revision =
        db.queryForObject("SELECT revision FROM booking_calendar WHERE id=1", Long.class);
    if (revision != request.revision())
      throw new ApiException(HttpStatus.CONFLICT, "다른 관리자가 휴무일을 변경했습니다. 새로고침 후 다시 확인해 주세요.");
    for (LocalDate date : request.overrides().keySet())
      if (date.getYear() < 1900 || date.getYear() > 2100) throw bad("날짜 범위를 확인해 주세요.");
    String days =
        request.closedDays().stream()
            .sorted()
            .map(Enum::name)
            .collect(java.util.stream.Collectors.joining(","));
    db.update("UPDATE booking_calendar SET closed_days=?,revision=revision+1 WHERE id=1", days);
    db.update("DELETE FROM booking_day_overrides");
    request
        .overrides()
        .forEach(
            (day, closed) ->
                db.update("INSERT INTO booking_day_overrides VALUES (?,?)", day, closed));
    return calendar();
  }

  public AppointmentModels.Policy view() {
    LocalDate today = now().atZone(zone).toLocalDate();
    return new AppointmentModels.Policy(
        zone.getId(),
        opensAt,
        closesAt,
        configuredDays(),
        SLOT_MINUTES,
        today,
        today.plusDays(horizonDays));
  }

  public void checkDate(LocalDate date) {
    var policy = view();
    if (date == null || date.isBefore(policy.earliestDate()) || date.isAfter(policy.latestDate())) {
      throw bad("예약 날짜는 오늘부터 " + horizonDays + "일 이내로 선택해 주세요.");
    }
  }

  public OffsetDateTime checkStart(OffsetDateTime requested, int minutes) {
    if (requested == null) throw bad("예약 시간을 선택해 주세요.");
    var start = requested.atZoneSameInstant(zone).toOffsetDateTime();
    checkDate(start.toLocalDate());
    var end = start.plusMinutes(minutes);
    if (closed(start.toLocalDate())
        || !aligned(start.toLocalTime())
        || start.toLocalTime().isBefore(opensAt)
        || !end.toLocalDate().equals(start.toLocalDate())
        || end.toLocalTime().isAfter(closesAt)) {
      throw bad("운영시간 내에서 30분 단위로 예약해 주세요. 정비 종료 시간도 운영시간 안이어야 합니다.");
    }
    if (!start.toInstant().isAfter(now())) throw bad("이미 지난 시간에는 예약할 수 없습니다.");
    return start;
  }

  private static boolean aligned(LocalTime t) {
    return t.getMinute() % SLOT_MINUTES == 0 && t.getSecond() == 0 && t.getNano() == 0;
  }

  private static ApiException bad(String message) {
    return new ApiException(HttpStatus.BAD_REQUEST, message);
  }

  @Configuration
  static class TimeConfiguration {
    @Bean
    Clock bookingClock() {
      return Clock.systemUTC();
    }
  }
}
