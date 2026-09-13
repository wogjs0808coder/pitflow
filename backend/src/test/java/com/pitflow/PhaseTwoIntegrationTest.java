package com.pitflow;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pitflow.appointment.AppointmentModels.*;
import com.pitflow.appointment.AppointmentService;
import com.pitflow.catalog.*;
import com.pitflow.user.*;
import com.pitflow.vehicle.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.*;
import org.springframework.context.annotation.*;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(PhaseTwoIntegrationTest.TimeConfig.class)
class PhaseTwoIntegrationTest {
  @Autowired MockMvc mvc;
  @Autowired ObjectMapper json;
  @Autowired JdbcTemplate db;
  @Autowired UserRepository users;
  @Autowired VehicleRepository vehicles;
  @Autowired ServiceItemRepository catalog;
  @Autowired AppointmentService bookings;
  @Autowired TestClock clock;
  UUID car, otherCar, oil, tires;
  static final UUID BAY1 = UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaa1");
  static final UUID BAY2 = UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaa2");
  static final OffsetDateTime START = OffsetDateTime.parse("2026-09-15T10:00:00+09:00");

  @TestConfiguration
  static class TimeConfig {
    @Bean
    @Primary
    TestClock testClock() {
      return new TestClock();
    }
  }

  static class TestClock extends Clock {
    final AtomicReference<Instant> time = new AtomicReference<>();

    void set(Instant value) {
      time.set(value);
    }

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return Clock.fixed(instant(), zone);
    }

    @Override
    public Instant instant() {
      return time.get();
    }
  }

  @BeforeEach
  void setup() {
    db.update("DELETE FROM booking_day_overrides");
    db.update("UPDATE booking_calendar SET revision=0,closed_days=NULL WHERE id=1");
    clock.set(Instant.parse("2026-09-14T00:00:00Z"));
    clear();
    vehicles.deleteAll();
    users.deleteAll();
    catalog.deleteAll();
    var owner =
        users.saveAndFlush(
            new AppUser("owner@example.com", "test-only-hash", "소유자", AppUser.Role.CUSTOMER));
    var other =
        users.saveAndFlush(
            new AppUser("other@example.com", "test-only-hash", "다른 고객", AppUser.Role.CUSTOMER));
    users.saveAndFlush(
        new AppUser("admin@example.com", "test-only-hash", "관리자", AppUser.Role.ADMIN));
    car =
        vehicles
            .saveAndFlush(
                new Vehicle(owner.getId(), new VehicleRequest("12가3456", "기아", "K3", 2024, 26400)))
            .getId();
    otherCar =
        vehicles
            .saveAndFlush(
                new Vehicle(other.getId(), new VehicleRequest("34나5678", "현대", "아반떼", 2024, 10000)))
            .getId();
    oil =
        catalog
            .saveAndFlush(
                new ServiceItem(
                    new ServiceRequest("오일", "시연용 공임", BigDecimal.valueOf(20000), 30, true)))
            .getId();
    tires =
        catalog
            .saveAndFlush(
                new ServiceItem(
                    new ServiceRequest("타이어", "시연용 공임", BigDecimal.valueOf(40000), 60, true)))
            .getId();
    db.update("UPDATE work_bays SET active = TRUE");
  }

  @AfterEach
  void clear() {
    db.update("DELETE FROM slot_allocations");
    db.update("DELETE FROM appointment_items");
    db.update("DELETE FROM appointments");
  }

  private CreateRequest request(UUID vehicle, UUID bay, OffsetDateTime start, UUID... services) {
    return new CreateRequest(vehicle, bay, List.of(services), start, "오일 상태 확인 부탁드립니다.");
  }

  private ResultActions createAs(String email, CreateRequest request) throws Exception {
    return mvc.perform(
        post("/api/appointments")
            .with(user(email))
            .with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(request)));
  }

  private JsonNode read(ResultActions result) throws Exception {
    return json.readTree(result.andReturn().getResponse().getContentAsString());
  }

  private UUID book(UUID... services) throws Exception {
    return UUID.fromString(
        read(createAs("owner@example.com", request(car, BAY1, START, services))
                .andExpect(status().isCreated()))
            .get("id")
            .asText());
  }

  private ResultActions change(UUID id, String status) throws Exception {
    return mvc.perform(
        patch("/api/admin/appointments/" + id + "/status")
            .with(user("admin@example.com").roles("ADMIN"))
            .with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"status\":\"" + status + "\"}"));
  }

  private long count(String table) {
    return db.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
  }

  @Test
  void bookingHoldsEverySlotAndPreservesQuotedItems() throws Exception {
    UUID id = book(oil, tires);
    assertThat(count("slot_allocations")).isEqualTo(3);
    var before = bookings.detail("owner@example.com", id);
    assertThat(before.status()).isEqualTo(Status.PENDING);
    assertThat(before.endsAt()).isEqualTo(START.plusMinutes(90));
    assertThat(before.totalLaborPrice()).isEqualByComparingTo("60000");
    var changed = catalog.findById(oil).orElseThrow();
    changed.update(new ServiceRequest("변경된 오일", "설명", BigDecimal.valueOf(90000), 60, false));
    catalog.saveAndFlush(changed);
    assertThat(bookings.detail("owner@example.com", id).items()).isEqualTo(before.items());
    assertThat(bookings.detail("owner@example.com", id).totalLaborPrice())
        .isEqualByComparingTo("60000");
  }

  @Test
  void laterSlotConflictRollsBackWholeBookingAndItems() throws Exception {
    createAs("other@example.com", request(otherCar, BAY1, START.plusMinutes(30), oil))
        .andExpect(status().isCreated());
    createAs("owner@example.com", request(car, BAY1, START, oil, tires))
        .andExpect(status().isConflict());
    assertThat(count("appointments")).isEqualTo(1);
    assertThat(count("appointment_items")).isEqualTo(1);
    assertThat(count("slot_allocations")).isEqualTo(1);
    createAs("owner@example.com", request(car, BAY1, START, oil)).andExpect(status().isCreated());
  }

  @Test
  void adjacentBookingsAndDifferentBaysAllowDifferentCars() throws Exception {
    book(oil);
    createAs("other@example.com", request(otherCar, BAY2, START, oil))
        .andExpect(status().isCreated());
    createAs("owner@example.com", request(car, BAY1, START.plusMinutes(30), oil))
        .andExpect(status().isCreated());
    assertThat(count("appointments")).isEqualTo(3);
  }

  @Test
  void sameCarCannotOccupyTwoBays() throws Exception {
    book(oil, tires);
    createAs("owner@example.com", request(car, BAY2, START.plusMinutes(30), oil))
        .andExpect(status().isConflict());
    var available =
        bookings.availability("owner@example.com", car, START.toLocalDate(), List.of(oil));
    assertThat(available.slots())
        .noneMatch(
            s -> !s.startsAt().isBefore(START) && s.startsAt().isBefore(START.plusMinutes(90)));
  }

  @Test
  void availabilityRequiresEnoughConsecutiveSlotsInOneBay() throws Exception {
    createAs("other@example.com", request(otherCar, BAY1, START.plusMinutes(30), oil))
        .andExpect(status().isCreated());
    var available =
        bookings.availability("owner@example.com", car, START.toLocalDate(), List.of(tires));
    Slot slot =
        available.slots().stream()
            .filter(s -> s.startsAt().equals(START))
            .findFirst()
            .orElseThrow();
    assertThat(slot.availableBays()).extracting(Bay::id).containsExactly(BAY2);
  }

  @Test
  void cancelIsIdempotentAndReleasesSlotsButPreservesHistory() throws Exception {
    UUID id = book(oil, tires);
    for (int n = 0; n < 2; n++) {
      mvc.perform(
              post("/api/appointments/" + id + "/cancel")
                  .with(user("owner@example.com"))
                  .with(csrf()))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.status").value("CANCELLED"));
    }
    assertThat(count("slot_allocations")).isZero();
    assertThat(count("appointment_items")).isEqualTo(2);
    createAs("other@example.com", request(otherCar, BAY1, START, oil))
        .andExpect(status().isCreated());
    change(id, "CONFIRMED").andExpect(status().isConflict());
    mvc.perform(delete("/api/vehicles/" + car).with(user("owner@example.com")).with(csrf()))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.message").value("예약 이력이 있는 차량은 삭제할 수 없습니다."));
  }

  @Test
  void ownerIsolationAdminAuthorizationAndCsrfAreEnforced() throws Exception {
    UUID id = book(oil);
    mvc.perform(get("/api/appointments/policy")).andExpect(status().isUnauthorized());
    mvc.perform(get("/api/appointments/" + id).with(user("other@example.com")))
        .andExpect(status().isNotFound());
    mvc.perform(
            post("/api/appointments/" + id + "/cancel")
                .with(user("other@example.com"))
                .with(csrf()))
        .andExpect(status().isNotFound());
    createAs("other@example.com", request(car, BAY2, START, oil)).andExpect(status().isNotFound());
    mvc.perform(
            get("/api/appointments/availability")
                .with(user("other@example.com"))
                .param("vehicleId", car.toString())
                .param("date", "2026-09-15")
                .param("serviceIds", oil.toString()))
        .andExpect(status().isNotFound());
    mvc.perform(
            get("/api/appointments")
                .with(user("other@example.com"))
                .param("from", "2026-09-15")
                .param("to", "2026-09-15"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(0));
    mvc.perform(
            get("/api/admin/appointments")
                .with(user("owner@example.com"))
                .param("from", "2026-09-15")
                .param("to", "2026-09-15"))
        .andExpect(status().isForbidden());
    mvc.perform(
            patch("/api/admin/appointments/" + id + "/status")
                .with(user("owner@example.com"))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"CONFIRMED\"}"))
        .andExpect(status().isForbidden());
    mvc.perform(post("/api/appointments/" + id + "/cancel").with(user("owner@example.com")))
        .andExpect(status().isForbidden());
    mvc.perform(
            post("/api/appointments")
                .with(user("owner@example.com"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(request(car, BAY2, START, oil))))
        .andExpect(status().isForbidden());
    mvc.perform(
            patch("/api/admin/appointments/" + id + "/status")
                .with(user("admin@example.com").roles("ADMIN"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"CONFIRMED\"}"))
        .andExpect(status().isForbidden());
    mvc.perform(
            get("/api/admin/appointments")
                .with(user("admin@example.com").roles("ADMIN"))
                .param("from", "2026-09-15")
                .param("to", "2026-09-15"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].customerEmail").value("owner@example.com"));
  }

  @Test
  void invalidDatesDurationsAndOffsetsAreHandled() throws Exception {
    for (String time :
        List.of(
            "2026-09-13T10:00:00+09:00",
            "2026-10-15T10:00:00+09:00",
            "2026-09-20T10:00:00+09:00",
            "2026-09-15T08:30:00+09:00",
            "2026-09-15T10:15:00+09:00",
            "2026-09-15T10:00:01+09:00",
            "2026-09-15T10:00:00.001+09:00",
            "2026-09-14T09:00:00+09:00")) {
      createAs("owner@example.com", request(car, BAY1, OffsetDateTime.parse(time), oil))
          .andExpect(status().isBadRequest());
    }
    createAs("owner@example.com", request(car, BAY1, START.withHour(17).withMinute(30), tires))
        .andExpect(status().isBadRequest());
    // An explicit UTC timestamp must mean the same instant as Korean local time.
    createAs(
            "owner@example.com",
            request(car, BAY1, START.withOffsetSameInstant(ZoneOffset.UTC), oil))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.startsAt").value("2026-09-15T10:00:00+09:00"));
    assertThat(
            bookings
                .availability("owner@example.com", car, LocalDate.parse("2026-09-20"), List.of(oil))
                .slots())
        .isEmpty();
    mvc.perform(
            get("/api/appointments")
                .with(user("owner@example.com"))
                .param("from", "2026-01-01")
                .param("to", "2026-12-31"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void invalidSelectionsAndForgedServerFieldsAreRejected() throws Exception {
    for (List<UUID> ids : List.of(List.<UUID>of(), List.of(oil, oil), List.of(UUID.randomUUID()))) {
      createAs("owner@example.com", new CreateRequest(car, BAY1, ids, START, ""))
          .andExpect(status().isBadRequest());
    }
    db.update("UPDATE service_items SET active = FALSE WHERE id = ?", oil);
    createAs("owner@example.com", request(car, BAY1, START, oil))
        .andExpect(status().isBadRequest());
    db.update("UPDATE work_bays SET active = FALSE WHERE id = ?", BAY1);
    createAs("owner@example.com", request(car, BAY1, START, tires))
        .andExpect(status().isBadRequest());
    ObjectNode body = json.valueToTree(request(car, BAY2, START, tires));
    body.put("customerId", UUID.randomUUID().toString());
    mvc.perform(
            post("/api/appointments")
                .with(user("owner@example.com"))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body.toString()))
        .andExpect(status().isBadRequest());
    db.update("UPDATE service_items SET active = TRUE, duration_minutes = 480 WHERE id = ?", oil);
    createAs("owner@example.com", request(car, BAY2, START, oil, tires))
        .andExpect(status().isBadRequest());
  }

  @Test
  void transitionsRespectTimeAndTerminalStates() throws Exception {
    UUID id = book(oil);
    change(id, "VISITED").andExpect(status().isConflict());
    change(id, "NO_SHOW").andExpect(status().isConflict());
    change(id, "CONFIRMED").andExpect(status().isOk());
    change(id, "CONFIRMED").andExpect(status().isOk());
    change(id, "VISITED").andExpect(status().isOk());
    clock.set(START.toInstant());
    mvc.perform(
            post("/api/appointments/" + id + "/cancel")
                .with(user("owner@example.com"))
                .with(csrf()))
        .andExpect(status().isConflict());
    change(id, "VISITED").andExpect(status().isOk());
    change(id, "CANCELLED").andExpect(status().isConflict());
    assertThat(count("slot_allocations")).isEqualTo(1);
  }

  @Test
  void noShowCanOnlyBeRecordedAfterEndAndCannotBeReopened() throws Exception {
    UUID id = book(oil);
    clock.set(START.plusMinutes(30).toInstant());
    change(id, "CONFIRMED").andExpect(status().isConflict());
    change(id, "NO_SHOW").andExpect(status().isOk());
    change(id, "CONFIRMED").andExpect(status().isConflict());
    assertThat(count("slot_allocations")).isZero();
  }

  @Test
  void simultaneousCustomersCompetingForOneBayProduceOneSuccessAndNoPartialData() throws Exception {
    var results =
        race(
            () ->
                createAs("owner@example.com", request(car, BAY1, START, oil, tires))
                    .andReturn()
                    .getResponse()
                    .getStatus(),
            () ->
                createAs("other@example.com", request(otherCar, BAY1, START, oil, tires))
                    .andReturn()
                    .getResponse()
                    .getStatus());
    assertThat(results).containsExactlyInAnyOrder(201, 409);
    assertThat(count("appointments")).isEqualTo(1);
    assertThat(count("appointment_items")).isEqualTo(2);
    assertThat(count("slot_allocations")).isEqualTo(3);
  }

  @Test
  void concurrentConfirmAndCancelCannotResurrectSlots() throws Exception {
    UUID id = book(oil, tires);
    var results =
        race(
            () -> change(id, "CONFIRMED").andReturn().getResponse().getStatus(),
            () ->
                mvc.perform(
                        post("/api/appointments/" + id + "/cancel")
                            .with(user("owner@example.com"))
                            .with(csrf()))
                    .andReturn()
                    .getResponse()
                    .getStatus());
    assertThat(results.get(0)).isIn(200, 409);
    assertThat(results.get(1)).isEqualTo(200);
    assertThat(bookings.detail("owner@example.com", id).status()).isEqualTo(Status.CANCELLED);
    assertThat(count("slot_allocations")).isZero();
  }

  @Test
  void calendarOverridesPreserveExistingBookingsAndRequireAdminCsrfAndRevision() throws Exception {
    UUID existing = book(oil);
    String body =
        json.writeValueAsString(
            Map.of(
                "revision",
                0,
                "closedDays",
                List.of("TUESDAY", "SUNDAY"),
                "overrides",
                Map.of("2026-09-20", false)));
    String path = "/api/admin/booking-calendar";
    mvc.perform(
            put(path)
                .with(user("owner@example.com"))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isForbidden());
    mvc.perform(
            put(path)
                .with(user("admin@example.com").roles("ADMIN"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isForbidden());
    mvc.perform(
            put(path)
                .with(user("admin@example.com").roles("ADMIN"))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isOk());
    mvc.perform(
            put(path)
                .with(user("admin@example.com").roles("ADMIN"))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isConflict());
    assertThat(bookings.detail("owner@example.com", existing).status()).isEqualTo(Status.PENDING);
    createAs("other@example.com", request(otherCar, BAY2, START, oil))
        .andExpect(status().isBadRequest());
    assertThat(
            bookings
                .availability("owner@example.com", car, START.toLocalDate(), List.of(oil))
                .closed())
        .isTrue();
    createAs("other@example.com", request(otherCar, BAY2, START.plusDays(5), oil))
        .andExpect(status().isCreated());
    mvc.perform(get(path).with(user("admin@example.com").roles("ADMIN")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.revision").value(1));
  }

  @Test
  void concurrentCalendarEditsCannotOverwriteEachOther() throws Exception {
    String path = "/api/admin/booking-calendar";
    Callable<Integer> edit =
        () ->
            mvc.perform(
                    put(path)
                        .with(user("admin@example.com").roles("ADMIN"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                            json.writeValueAsString(
                                Map.of(
                                    "revision",
                                    0,
                                    "closedDays",
                                    List.of(),
                                    "overrides",
                                    Map.of("2026-09-16", true)))))
                .andReturn()
                .getResponse()
                .getStatus();
    assertThat(race(edit, edit)).containsExactlyInAnyOrder(200, 409);
  }

  private List<Integer> race(Callable<Integer> first, Callable<Integer> second) throws Exception {
    var gate = new CyclicBarrier(2);
    var executor = Executors.newFixedThreadPool(2);
    try {
      var a =
          executor.submit(
              () -> {
                gate.await(10, TimeUnit.SECONDS);
                return first.call();
              });
      var b =
          executor.submit(
              () -> {
                gate.await(10, TimeUnit.SECONDS);
                return second.call();
              });
      return List.of(a.get(20, TimeUnit.SECONDS), b.get(20, TimeUnit.SECONDS));
    } finally {
      executor.shutdownNow();
      assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
    }
  }
}
