package com.pitflow.appointment;

import static com.pitflow.appointment.AppointmentModels.*;

import com.pitflow.common.ApiException;
import com.pitflow.user.UserRepository;
import java.math.BigDecimal;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class AppointmentService {
  private final AppointmentRepository repository;
  private final UserRepository users;
  private final BookingPolicy policy;

  public AppointmentService(
      AppointmentRepository repository, UserRepository users, BookingPolicy policy) {
    this.repository = repository;
    this.users = users;
    this.policy = policy;
  }

  public Policy policy() {
    return policy.view();
  }

  public List<Bay> bays() {
    return repository.bays();
  }

  private UUID customer(String email) {
    return users
        .findByEmail(email)
        .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "로그인이 필요합니다."))
        .getId();
  }

  private Car car(UUID vehicle, UUID customer, boolean lock) {
    return repository
        .ownedCar(vehicle, customer, lock)
        .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "차량을 찾을 수 없습니다."));
  }

  private List<Item> selection(List<UUID> ids) {
    if (ids == null
        || ids.isEmpty()
        || ids.size() > 16
        || ids.stream().anyMatch(Objects::isNull)
        || new HashSet<>(ids).size() != ids.size()) {
      throw bad("정비 항목을 중복 없이 1~16개 선택해 주세요.");
    }
    var items = repository.catalog(ids);
    if (items.size() != ids.size()) throw bad("선택한 정비 항목이 없거나 더 이상 예약할 수 없습니다. 다시 선택해 주세요.");
    int duration =
        items.stream().mapToInt(item -> item.durationMinutes() * item.quantity()).sum();
    if (duration > 480) throw bad("한 번에 예약할 수 있는 정비 시간은 최대 480분입니다.");
    return items;
  }

  public Availability availability(
      String email, UUID vehicleId, LocalDate date, List<UUID> serviceIds) {
    car(vehicleId, customer(email), false);
    policy.checkDate(date);
    var items = selection(serviceIds);
    int duration =
        items.stream().mapToInt(item -> item.durationMinutes() * item.quantity()).sum();
    var total =
        items.stream()
            .map(item -> item.laborPrice().multiply(BigDecimal.valueOf(item.quantity())))
            .reduce(BigDecimal.ZERO, BigDecimal::add);
    List<Slot> slots = new ArrayList<>();
    if (!policy.closed(date)) {
      var bays = repository.bays();
      var start = policy.at(date, policy.opensAt());
      var close = policy.at(date, policy.closesAt());
      var occupied = repository.occupied(start, close);
      for (var time = start;
          !time.plusMinutes(duration).isAfter(close);
          time = time.plusMinutes(30)) {
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
                                    (o.bayId().equals(bay.id()) || o.vehicleId().equals(vehicleId))
                                        && !o.startsAt().isBefore(from)
                                        && o.startsAt().isBefore(to)))
                .toList();
        if (!available.isEmpty()) slots.add(new Slot(time, time.plusMinutes(duration), available));
      }
    }
    return new Availability(date, policy.view(), policy.closed(date), duration, total, slots);
  }

  @Transactional
  public View create(String email, CreateRequest request) {
    policy.lockCalendar();
    UUID customer = customer(email);
    // Serialize reservations and deletion for this car. Different customers can still book
    // concurrently.
    var car = car(request.vehicleId(), customer, true);
    var items = selection(request.serviceIds());
    if (repository.bays().stream().noneMatch(b -> b.id().equals(request.workBayId()))) {
      throw bad("예약 가능한 작업 공간을 선택해 주세요.");
    }
    var start =
        policy.checkStart(
            request.startsAt(),
            items.stream().mapToInt(item -> item.durationMinutes() * item.quantity()).sum());
    UUID id = UUID.randomUUID();
    try {
      repository.insert(
          id,
          customer,
          car,
          request.workBayId(),
          start,
          items,
          request.notes() == null ? "" : request.notes().strip(),
          policy.now());
    } catch (DuplicateKeyException ex) {
      // Throw out of the transactional boundary; never continue after a failed PostgreSQL
      // statement.
      throw conflict("선택한 시간에 다른 예약이 있습니다. 예약 가능한 시간을 다시 조회해 주세요.");
    }
    return detail(email, id);
  }

  public View detail(String email, UUID id) {
    var row = repository.find(id, customer(email)).orElseThrow(AppointmentService::notFound);
    return views(List.of(row), false).get(0);
  }

  public List<View> list(String email, LocalDate from, LocalDate to) {
    return listRange(customer(email), from, to, false);
  }

  public List<View> adminList(LocalDate from, LocalDate to) {
    return listRange(null, from, to, true);
  }

  private List<View> listRange(UUID customer, LocalDate from, LocalDate to, boolean admin) {
    if (from == null
        || to == null
        || from.getYear() < 1900
        || to.getYear() > 2100
        || to.isBefore(from)
        || ChronoUnit.DAYS.between(from, to) > 62) {
      throw bad("조회 기간은 시작일 이후 최대 62일로 선택해 주세요.");
    }
    return views(
        repository.list(
            customer,
            policy.at(from, LocalTime.MIDNIGHT),
            policy.at(to.plusDays(1), LocalTime.MIDNIGHT)),
        admin);
  }

  @Transactional
  public View cancel(String email, UUID id) {
    UUID customer = customer(email);
    if (!repository.lock(id, customer)) throw notFound();
    Row row = repository.find(id, customer).orElseThrow(AppointmentService::notFound);
    if (row.status() == Status.CANCELLED) return views(List.of(row), false).get(0);
    if (!allowed(row, false).contains(Status.CANCELLED)) {
      throw conflict("시작 전인 대기·확정 예약만 취소할 수 있습니다. 정비소에 문의해 주세요.");
    }
    repository.change(id, Status.CANCELLED, policy.now());
    return detail(email, id);
  }

  @Transactional
  public View change(UUID id, Status target) {
    if (!repository.lock(id, null)) throw notFound();
    Row row = repository.find(id, null).orElseThrow(AppointmentService::notFound);
    if (row.status() == target) return views(List.of(row), true).get(0);
    if (!allowed(row, true).contains(target)) {
      throw conflict("현재 상태 또는 시간에는 요청한 상태로 변경할 수 없습니다. 목록을 새로고침해 주세요.");
    }
    repository.change(id, target, policy.now());
    return views(List.of(repository.find(id, null).orElseThrow()), true).get(0);
  }

  private List<Status> allowed(Row row, boolean admin) {
    Instant now = policy.now();
    boolean pending = row.status() == Status.PENDING;
    boolean confirmed = row.status() == Status.CONFIRMED;
    if (!pending && !confirmed) return List.of();
    if (!admin)
      return now.isBefore(row.startsAt().toInstant()) ? List.of(Status.CANCELLED) : List.of();
    List<Status> result = new ArrayList<>();
    if (pending && now.isBefore(row.endsAt().toInstant())) result.add(Status.CONFIRMED);
    result.add(Status.CANCELLED);
    // Confirmed customers may arrive on an earlier date; preserve the original reservation.
    if (confirmed && now.isBefore(row.endsAt().toInstant())) result.add(Status.VISITED);
    if (!now.isBefore(row.endsAt().toInstant())) result.add(Status.NO_SHOW);
    return List.copyOf(result);
  }

  private List<View> views(List<Row> rows, boolean admin) {
    var items = repository.items(rows.stream().map(Row::id).toList());
    return rows.stream()
        .map(
            r ->
                new View(
                    r.id(),
                    r.vehicleId(),
                    r.plateNumber(),
                    r.vehicleLabel(),
                    r.workBayId(),
                    r.workBayName(),
                    r.customerName(),
                    r.customerEmail(),
                    r.startsAt().atZoneSameInstant(policy.zone()).toOffsetDateTime(),
                    r.endsAt().atZoneSameInstant(policy.zone()).toOffsetDateTime(),
                    r.status(),
                    r.notes(),
                    r.totalLaborPrice(),
                    r.durationMinutes(),
                    items.getOrDefault(r.id(), List.of()),
                    allowed(r, admin)))
        .toList();
  }

  private static ApiException bad(String message) {
    return new ApiException(HttpStatus.BAD_REQUEST, message);
  }

  private static ApiException conflict(String message) {
    return new ApiException(HttpStatus.CONFLICT, message);
  }

  private static ApiException notFound() {
    return new ApiException(HttpStatus.NOT_FOUND, "예약을 찾을 수 없습니다.");
  }
}
