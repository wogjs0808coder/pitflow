package com.pitflow.appointment;

import com.pitflow.common.ApiException;
import java.time.*;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
public class BookingPolicy {
  public static final int SLOT_MINUTES = 30;
  private final Clock clock;
  private final ZoneId zone;
  private final LocalTime opensAt;
  private final LocalTime closesAt;
  private final int horizonDays;
  private final Set<DayOfWeek> closedDays;

  public BookingPolicy(
      Clock clock,
      @Value("${pitflow.booking.timezone:Asia/Seoul}") String timezone,
      @Value("${pitflow.booking.opens-at:09:00}") String opensAt,
      @Value("${pitflow.booking.closes-at:18:00}") String closesAt,
      @Value("${pitflow.booking.horizon-days:30}") int horizonDays,
      @Value("${pitflow.booking.closed-days:SUNDAY}") Set<DayOfWeek> closedDays) {
    this.clock = clock;
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
    return closedDays.contains(date.getDayOfWeek());
  }

  public AppointmentModels.Policy view() {
    LocalDate today = now().atZone(zone).toLocalDate();
    return new AppointmentModels.Policy(
        zone.getId(),
        opensAt,
        closesAt,
        closedDays,
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
