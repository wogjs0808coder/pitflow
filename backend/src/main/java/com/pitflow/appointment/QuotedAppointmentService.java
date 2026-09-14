package com.pitflow.appointment;

import static com.pitflow.appointment.AppointmentModels.*;

import com.pitflow.common.ApiException;
import com.pitflow.user.UserRepository;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class QuotedAppointmentService {
  private final AppointmentRepository repository;
  private final UserRepository users;
  private final BookingPolicy policy;
  private final AppointmentService appointments;

  public QuotedAppointmentService(
      AppointmentRepository repository,
      UserRepository users,
      BookingPolicy policy,
      AppointmentService appointments) {
    this.repository = repository;
    this.users = users;
    this.policy = policy;
    this.appointments = appointments;
  }

  public Availability availability(String email, QuoteAvailabilityRequest request) {
    UUID customer = customer(email);
    repository
        .ownedCar(request.vehicleId(), customer, false)
        .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "차량을 찾을 수 없습니다."));
    policy.checkDate(request.date());
    Quote quote = appointments.quote(new QuoteRequest(request.items()));
    int duration = quote.durationMinutes();
    List<Slot> slots = new ArrayList<>();
    if (!policy.closed(request.date())) {
      var bays = repository.bays();
      var start = policy.at(request.date(), policy.opensAt());
      var close = policy.at(request.date(), policy.closesAt());
      var occupied = repository.occupied(start, close);
      for (var time = start;
          !time.plusMinutes(duration).isAfter(close);
          time = time.plusMinutes(BookingPolicy.SLOT_MINUTES)) {
        if (!time.toInstant().isAfter(policy.now())) continue;
        Instant from = time.toInstant();
        Instant to = time.plusMinutes(duration).toInstant();
        var available =
            bays.stream()
                .filter(
                    bay ->
                        occupied.stream()
                            .noneMatch(
                                o ->
                                    (o.bayId().equals(bay.id())
                                            || o.vehicleId().equals(request.vehicleId()))
                                        && !o.startsAt().isBefore(from)
                                        && o.startsAt().isBefore(to)))
                .toList();
        if (!available.isEmpty()) slots.add(new Slot(time, time.plusMinutes(duration), available));
      }
    }
    return new Availability(
        request.date(),
        policy.view(),
        policy.closed(request.date()),
        duration,
        quote.totalLaborPrice(),
        slots);
  }

  @Transactional
  public View create(String email, CreateRequest request) {
    if (request.items() == null || request.items().isEmpty()) {
      throw bad("견적 항목을 다시 확인해 주세요.");
    }
    if (request.serviceIds() != null && !request.serviceIds().isEmpty()) {
      throw bad("기존 serviceIds와 새 items 형식을 동시에 보낼 수 없습니다.");
    }
    if (request.quoteFingerprint() == null || request.quoteFingerprint().isBlank()) {
      throw bad("예약 전에 최신 견적을 다시 확인해 주세요.");
    }

    Quote quote = appointments.quote(new QuoteRequest(request.items()));
    if (!quote.fingerprint().equals(request.quoteFingerprint())) {
      throw conflict("정비 가격 또는 구성이 변경되었습니다. 최신 견적을 다시 확인해 주세요.");
    }

    policy.lockCalendar();
    UUID customer = customer(email);
    var car =
        repository
            .ownedCar(request.vehicleId(), customer, true)
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "차량을 찾을 수 없습니다."));
    if (repository.bays().stream().noneMatch(b -> b.id().equals(request.workBayId()))) {
      throw bad("예약 가능한 작업 공간을 선택해 주세요.");
    }
    var start = policy.checkStart(request.startsAt(), quote.durationMinutes());
    List<Item> items =
        quote.items().stream()
            .map(
                item ->
                    new Item(
                        item.serviceId(),
                        item.name(),
                        item.laborUnitPrice(),
                        item.durationMinutesPerService(),
                        item.quantity()))
            .toList();
    UUID id = UUID.randomUUID();
    try {
      repository.insert(
          id,
          customer,
          car,
          request.workBayId(),
          start,
          items,
          quote,
          request.notes() == null ? "" : request.notes().strip(),
          policy.now());
    } catch (DuplicateKeyException ex) {
      throw conflict("선택한 시간에 다른 예약이 있습니다. 예약 가능한 시간을 다시 조회해 주세요.");
    }
    return appointments.detail(email, id);
  }

  private UUID customer(String email) {
    return users
        .findByEmail(email)
        .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "로그인이 필요합니다."))
        .getId();
  }

  private static ApiException bad(String message) {
    return new ApiException(HttpStatus.BAD_REQUEST, message);
  }

  private static ApiException conflict(String message) {
    return new ApiException(HttpStatus.CONFLICT, message);
  }
}
