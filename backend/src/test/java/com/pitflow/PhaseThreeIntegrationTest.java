package com.pitflow;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

import com.fasterxml.jackson.databind.*;
import com.pitflow.user.*;
import com.pitflow.vehicle.*;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PhaseThreeIntegrationTest {
  @Autowired MockMvc mvc;
  @Autowired ObjectMapper json;
  @Autowired JdbcTemplate db;
  @Autowired UserRepository users;
  @Autowired VehicleRepository vehicles;
  UUID car, customer, mechanic, appointment, serviceItem;
  final String admin = "work-admin@example.com";

  @BeforeEach
  void setup() throws Exception {
    clean();
    customer =
        users
            .saveAndFlush(
                new AppUser("work-customer@example.com", "test-hash", "고객", AppUser.Role.CUSTOMER))
            .getId();
    users.saveAndFlush(new AppUser(admin, "test-hash", "관리자", AppUser.Role.ADMIN));
    users.saveAndFlush(
        new AppUser("work-other-admin@example.com", "test-hash", "다른 관리자", AppUser.Role.ADMIN));
    car =
        vehicles
            .saveAndFlush(
                new Vehicle(customer, new VehicleRequest("99가1234", "기아", "K3", 2024, 26400)))
            .getId();
    mechanic =
        UUID.fromString(
            ok("POST", "/api/admin/mechanics", Map.of("code", "M1", "name", "정비사", "active", true))
                .get("id")
                .asText());
    serviceItem = UUID.randomUUID();
    db.update(
        "INSERT INTO service_items VALUES (?,?,?,?,?,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
        serviceItem,
        "테스트 정비",
        "설명",
        20000,
        30,
        true);
    appointment = appointment();
  }

  @AfterEach
  void clean() {
    // Disposable test DB only: production has no delete endpoints for these records.
    db.update("DELETE FROM stock_movements WHERE kind='RETURN'");
    db.update("DELETE FROM stock_movements");
    db.update("DELETE FROM work_order_events");
    db.update("DELETE FROM work_order_items");
    db.update("DELETE FROM work_orders");
    db.update("DELETE FROM stock_operations");
    db.update("DELETE FROM mechanics");
    db.update("DELETE FROM parts");
    db.update("DELETE FROM slot_allocations");
    db.update("DELETE FROM appointment_items");
    db.update("DELETE FROM appointments");
    vehicles.deleteAll();
    users.deleteAll();
    db.update("DELETE FROM service_items");
  }

  UUID appointment() {
    UUID id = UUID.randomUUID();
    var start = OffsetDateTime.parse("2026-09-15T10:00:00+09:00");
    db.update(
        "INSERT INTO appointments"
            + " (id,customer_id,vehicle_id,work_bay_id,plate_number,vehicle_label,starts_at,ends_at,status,notes,total_labor_price,duration_minutes,created_at,updated_at)"
            + " VALUES (?,?,?,?,?,?,?,?,?,'',20000,30,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
        id,
        customer,
        car,
        UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaa1"),
        "99가1234",
        "기아 K3",
        start,
        start.plusMinutes(30),
        "VISITED");
    db.update(
        "INSERT INTO appointment_items VALUES (?,?,?,?,?)", id, serviceItem, "예약 당시 정비", 20000, 30);
    return id;
  }

  MvcResult request(String method, String path, Object body, UUID key, String email, boolean csrf)
      throws Exception {
    MockHttpServletRequestBuilder req =
        switch (method) {
          case "POST" -> post(path);
          case "DELETE" -> delete(path);
          default -> patch(path);
        };
    req.with(user(email).roles(email.contains("admin") ? "ADMIN" : "CUSTOMER"));
    if (csrf) req.with(csrf());
    if (key != null) req.header("Idempotency-Key", key.toString());
    return mvc.perform(
            req.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)))
        .andReturn();
  }

  JsonNode ok(String method, String path, Object body) throws Exception {
    var result = request(method, path, body, UUID.randomUUID(), admin, true);
    assertThat(result.getResponse().getStatus())
        .withFailMessage(result.getResponse().getContentAsString())
        .isEqualTo(200);
    return json.readTree(result.getResponse().getContentAsString());
  }

  Map<String, Object> receiveBody(UUID a, int mileage) {
    return Map.of(
        "appointmentId", a, "receivedMileage", mileage, "mechanicId", mechanic, "notes", "확인");
  }

  UUID work(UUID a) throws Exception {
    return UUID.fromString(
        ok("POST", "/api/admin/work-orders/from-appointment", receiveBody(a, 27000))
            .get("id")
            .asText());
  }

  UUID running() throws Exception {
    UUID w = work(appointment);
    ok("PATCH", "/api/admin/work-orders/" + w + "/status", Map.of("status", "IN_PROGRESS"));
    return w;
  }

  UUID part(String sku, String amount) throws Exception {
    UUID p =
        UUID.fromString(
            ok(
                    "POST",
                    "/api/admin/parts",
                    Map.of(
                        "sku",
                        sku,
                        "name",
                        sku,
                        "unit",
                        "L",
                        "minimumQuantity",
                        "1.000",
                        "unitPrice",
                        10000,
                        "active",
                        true))
                .get("id")
                .asText());
    if (new BigDecimal(amount).signum() > 0)
      ok(
          "POST",
          "/api/admin/parts/" + p + "/receipts",
          Map.of("quantity", amount, "reason", "실물 입고"));
    return p;
  }

  Object use(UUID p, String q) {
    return Map.of("lines", List.of(Map.of("partId", p, "quantity", q)), "reason", "정비 사용");
  }

  String usePath(UUID w) {
    return "/api/admin/work-orders/" + w + "/parts/use";
  }

  BigDecimal balance(UUID p) {
    return db.queryForObject("SELECT quantity FROM parts WHERE id=?", BigDecimal.class, p);
  }

  int count(String table) {
    return db.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
  }

  void reconciles(UUID p) {
    var net =
        db.queryForObject(
            "SELECT COALESCE(SUM(CASE WHEN kind IN ('USE','ADJUST_OUT') THEN -quantity ELSE"
                + " quantity END),0) FROM stock_movements WHERE part_id=?",
            BigDecimal.class,
            p);
    assertThat(balance(p)).isEqualByComparingTo(net);
  }

  @Test
  void correctionIsFractionalIdempotentValidatedAndAudited() throws Exception {
    UUID p = part("A", "4.5"), key = UUID.randomUUID();
    String path = "/api/admin/parts/" + p + "/adjustments";
    var body = Map.of("quantity", "1.125", "expectedQuantity", "4.5", "reason", "실사");
    for (int i = 0; i < 2; i++)
      assertThat(request("POST", path, body, key, admin, true).getResponse().getStatus())
          .isEqualTo(200);
    assertThat(balance(p)).isEqualByComparingTo("1.125");
    assertThat(
            db.queryForObject(
                "SELECT COUNT(*) FROM stock_movements WHERE kind='ADJUST_OUT'", Integer.class))
        .isEqualTo(1);
    ok("POST", path, Map.of("quantity", "2.25", "expectedQuantity", "1.125", "reason", "실사"));
    assertThat(
            request("POST", path, body, UUID.randomUUID(), admin, true).getResponse().getStatus())
        .isEqualTo(409);
    for (String q : List.of("-1", "0.0001"))
      assertThat(
              request(
                      "POST",
                      path,
                      Map.of("quantity", q, "expectedQuantity", "2.25", "reason", "실사"),
                      UUID.randomUUID(),
                      admin,
                      true)
                  .getResponse()
                  .getStatus())
          .isEqualTo(400);
    assertThat(
            request("POST", path, body, UUID.randomUUID(), admin, false).getResponse().getStatus())
        .isEqualTo(403);
    assertThat(
            request("POST", path, body, UUID.randomUUID(), "work-customer@example.com", true)
                .getResponse()
                .getStatus())
        .isEqualTo(403);
    reconciles(p);
  }

  @Test
  void correctionCannotOverwriteConcurrentUseOrCorrection() throws Exception {
    UUID w = running(), p = part("A", "5");
    String path = "/api/admin/parts/" + p + "/adjustments";
    var result =
        race(
            () ->
                request(
                        "POST",
                        path,
                        Map.of("quantity", "3", "expectedQuantity", "5", "reason", "실사"),
                        UUID.randomUUID(),
                        admin,
                        true)
                    .getResponse()
                    .getStatus(),
            () ->
                request("POST", usePath(w), use(p, "1"), UUID.randomUUID(), admin, true)
                    .getResponse()
                    .getStatus());
    assertThat(result.get(0)).isIn(200, 409);
    assertThat(result.get(1)).isEqualTo(200);
    assertThat(balance(p)).isEqualByComparingTo(result.get(0) == 200 ? "2" : "4");
    reconciles(p);
    var before = balance(p).toPlainString();
    result =
        race(
            () ->
                request(
                        "POST",
                        path,
                        Map.of("quantity", "0", "expectedQuantity", before, "reason", "실사1"),
                        UUID.randomUUID(),
                        admin,
                        true)
                    .getResponse()
                    .getStatus(),
            () ->
                request(
                        "POST",
                        path,
                        Map.of("quantity", "1", "expectedQuantity", before, "reason", "실사2"),
                        UUID.randomUUID(),
                        admin,
                        true)
                    .getResponse()
                    .getStatus());
    assertThat(result).containsExactlyInAnyOrder(200, 409);
    reconciles(p);
  }

  @Test
  void correctionFailureRollsBackKeyBalanceAndLedger() throws Exception {
    UUID p = part("A", "3"), key = UUID.randomUUID();
    db.execute(
        "ALTER TABLE stock_movements ADD CONSTRAINT test_no_adjust CHECK (kind NOT IN"
            + " ('ADJUST_IN','ADJUST_OUT'))");
    try {
      assertThat(
              request(
                      "POST",
                      "/api/admin/parts/" + p + "/adjustments",
                      Map.of("quantity", "0", "expectedQuantity", "3", "reason", "실사"),
                      key,
                      admin,
                      true)
                  .getResponse()
                  .getStatus())
          .isEqualTo(409);
      assertThat(balance(p)).isEqualByComparingTo("3");
      assertThat(
              db.queryForObject(
                  "SELECT COUNT(*) FROM stock_operations WHERE id=?", Integer.class, key))
          .isZero();
      reconciles(p);
    } finally {
      db.execute("ALTER TABLE stock_movements DROP CONSTRAINT test_no_adjust");
    }
  }

  @Test
  void archiveRenameRestoreKeepOriginalMovements() throws Exception {
    UUID p = part("A", "1");
    String path = "/api/admin/parts/" + p;
    assertThat(
            request("DELETE", path, Map.of("reason", "중복"), UUID.randomUUID(), admin, true)
                .getResponse()
                .getStatus())
        .isEqualTo(409);
    ok(
        "PATCH",
        path,
        Map.of(
            "sku",
            "A",
            "name",
            "수정된 이름",
            "unit",
            "L",
            "minimumQuantity",
            "0",
            "unitPrice",
            123,
            "active",
            true));
    assertThat(
            db.queryForObject(
                "SELECT part_name FROM stock_movements WHERE part_id=?", String.class, p))
        .isEqualTo("A");
    ok(
        "POST",
        path + "/adjustments",
        Map.of("quantity", "0", "expectedQuantity", "1", "reason", "오류 정정"));
    UUID key = UUID.randomUUID();
    for (int i = 0; i < 2; i++)
      assertThat(
              request("DELETE", path, Map.of("reason", "중복"), key, admin, true)
                  .getResponse()
                  .getStatus())
          .isEqualTo(200);
    var normal = mvc.perform(get("/api/admin/parts").with(user(admin).roles("ADMIN"))).andReturn();
    assertThat(json.readTree(normal.getResponse().getContentAsString()).size()).isZero();
    var all =
        mvc.perform(get("/api/admin/parts?includeArchived=true").with(user(admin).roles("ADMIN")))
            .andReturn();
    assertThat(
            json.readTree(all.getResponse().getContentAsString())
                .get(0)
                .get("archived")
                .asBoolean())
        .isTrue();
    assertThat(count("stock_movements")).isEqualTo(2);
    assertThat(count("part_events")).isEqualTo(1);
    assertThat(
            request(
                    "POST",
                    path + "/adjustments",
                    Map.of("quantity", "1", "expectedQuantity", "0", "reason", "실사"),
                    UUID.randomUUID(),
                    admin,
                    true)
                .getResponse()
                .getStatus())
        .isEqualTo(409);
    ok("POST", path + "/restore", Map.of("reason", "복원"));
    assertThat(db.queryForObject("SELECT active FROM parts WHERE id=?", Boolean.class, p))
        .isFalse();
    assertThat(db.queryForObject("SELECT archived FROM parts WHERE id=?", Boolean.class, p))
        .isFalse();
    reconciles(p);
  }

  @Test
  void physicalReturnRestoresArchivedPartButKeepsItInactive() throws Exception {
    UUID w = running(), p = part("A", "1");
    String original =
        ok("POST", usePath(w), use(p, "1")).get("movements").get(0).get("id").asText();
    ok("DELETE", "/api/admin/parts/" + p, Map.of("reason", "단종"));
    ok(
        "POST",
        "/api/admin/work-orders/" + w + "/parts/return",
        Map.of("originalUseId", original, "quantity", "0.5", "reason", "실물 회수"));
    assertThat(balance(p)).isEqualByComparingTo("0.5");
    assertThat(db.queryForObject("SELECT archived FROM parts WHERE id=?", Boolean.class, p))
        .isFalse();
    assertThat(db.queryForObject("SELECT active FROM parts WHERE id=?", Boolean.class, p))
        .isFalse();
    reconciles(p);
  }

  @Test
  void receiveIsUniquePreservesSnapshotAndMileage() throws Exception {
    UUID key = UUID.randomUUID();
    var body = receiveBody(appointment, 27000);
    var a = request("POST", "/api/admin/work-orders/from-appointment", body, key, admin, true);
    var b = request("POST", "/api/admin/work-orders/from-appointment", body, key, admin, true);
    assertThat(a.getResponse().getStatus()).isEqualTo(200);
    assertThat(json.readTree(a.getResponse().getContentAsString()))
        .isEqualTo(json.readTree(b.getResponse().getContentAsString()));
    assertThat(
            request(
                    "POST",
                    "/api/admin/work-orders/from-appointment",
                    body,
                    UUID.randomUUID(),
                    admin,
                    true)
                .getResponse()
                .getStatus())
        .isEqualTo(409);
    assertThat(count("work_orders")).isEqualTo(1);
    assertThat(db.queryForObject("SELECT mileage FROM vehicles WHERE id=?", Integer.class, car))
        .isEqualTo(27000);
    assertThat(db.queryForObject("SELECT name FROM work_order_items", String.class))
        .isEqualTo("예약 당시 정비");
  }

  @Test
  void receiveRejectsWrongStateLowerMileageAndInactiveMechanic() throws Exception {
    String path = "/api/admin/work-orders/from-appointment";
    assertThat(
            request("POST", path, receiveBody(appointment, 26000), UUID.randomUUID(), admin, true)
                .getResponse()
                .getStatus())
        .isEqualTo(409);
    db.update("UPDATE appointments SET status='CANCELLED' WHERE id=?", appointment);
    assertThat(
            request("POST", path, receiveBody(appointment, 27000), UUID.randomUUID(), admin, true)
                .getResponse()
                .getStatus())
        .isEqualTo(409);
    db.update("UPDATE appointments SET status='VISITED' WHERE id=?", appointment);
    db.update("UPDATE mechanics SET active=FALSE");
    assertThat(
            request("POST", path, receiveBody(appointment, 27000), UUID.randomUUID(), admin, true)
                .getResponse()
                .getStatus())
        .isEqualTo(409);
    assertThat(count("work_orders")).isZero();
  }

  @Test
  void fractionalUseReplayAndPayloadConflict() throws Exception {
    UUID w = running(), p = part("OIL", "2.000"), key = UUID.randomUUID();
    assertThat(
            request("POST", usePath(w), use(p, "0.125"), key, admin, true)
                .getResponse()
                .getStatus())
        .isEqualTo(200);
    assertThat(
            request("POST", usePath(w), use(p, "0.1250"), key, admin, true)
                .getResponse()
                .getStatus())
        .isEqualTo(400);
    assertThat(
            request("POST", usePath(w), use(p, "0.125"), key, admin, true)
                .getResponse()
                .getStatus())
        .isEqualTo(200);
    assertThat(
            request("POST", usePath(w), use(p, "0.250"), key, admin, true)
                .getResponse()
                .getStatus())
        .isEqualTo(409);
    assertThat(
            request("POST", usePath(w), use(p, "0.125"), key, "work-other-admin@example.com", true)
                .getResponse()
                .getStatus())
        .isEqualTo(409);
    assertThat(balance(p)).isEqualByComparingTo("1.875");
    reconciles(p);
  }

  @Test
  void shortageRollsBackAllAndSameKeyCanRetryAfterReceipt() throws Exception {
    UUID w = running(), p = part("A", "1"), p2 = part("B", "0"), key = UUID.randomUUID();
    Object body =
        Map.of(
            "lines",
            List.of(Map.of("partId", p, "quantity", "0.5"), Map.of("partId", p2, "quantity", "1")),
            "reason",
            "묶음 사용");
    assertThat(request("POST", usePath(w), body, key, admin, true).getResponse().getStatus())
        .isEqualTo(409);
    assertThat(balance(p)).isEqualByComparingTo("1");
    assertThat(
            db.queryForObject(
                "SELECT COUNT(*) FROM stock_operations WHERE id=?", Integer.class, key))
        .isZero();
    ok("POST", "/api/admin/parts/" + p2 + "/receipts", Map.of("quantity", "1", "reason", "입고"));
    assertThat(request("POST", usePath(w), body, key, admin, true).getResponse().getStatus())
        .isEqualTo(200);
    reconciles(p);
    reconciles(p2);
  }

  @Test
  void failureAfterFirstWriteRollsBackBalanceLedgerAndKey() throws Exception {
    UUID w = running(), p = part("A", "2"), key = UUID.randomUUID();
    // Force a DB failure after UPDATE parts; verify the entire transaction, not a pre-check.
    db.execute("ALTER TABLE stock_movements ADD CONSTRAINT test_no_use CHECK (kind <> 'USE')");
    try {
      assertThat(
              request("POST", usePath(w), use(p, "1"), key, admin, true).getResponse().getStatus())
          .isEqualTo(409);
      assertThat(balance(p)).isEqualByComparingTo("2");
      assertThat(
              db.queryForObject(
                  "SELECT COUNT(*) FROM stock_operations WHERE id=?", Integer.class, key))
          .isZero();
      reconciles(p);
    } finally {
      db.execute("ALTER TABLE stock_movements DROP CONSTRAINT test_no_use");
    }
  }

  @Test
  void cancellationNeverRestocksAndReturnsPreserveHistory() throws Exception {
    UUID w = running(), p = part("A", "2");
    var result = ok("POST", usePath(w), use(p, "1.5"));
    String original = result.get("movements").get(0).get("id").asText();
    ok(
        "PATCH",
        "/api/admin/work-orders/" + w + "/status",
        Map.of("status", "CANCELLED", "reason", "고객 요청"));
    assertThat(balance(p)).isEqualByComparingTo("0.5");
    UUID key = UUID.randomUUID();
    Object body = Map.of("originalUseId", original, "quantity", "0.5", "reason", "미사용 잔량 실물 반환");
    String path = "/api/admin/work-orders/" + w + "/parts/return";
    assertThat(request("POST", path, body, key, admin, true).getResponse().getStatus())
        .isEqualTo(200);
    assertThat(request("POST", path, body, key, admin, true).getResponse().getStatus())
        .isEqualTo(200);
    assertThat(
            request(
                    "POST",
                    path,
                    Map.of("originalUseId", original, "quantity", "1.001", "reason", "초과"),
                    UUID.randomUUID(),
                    admin,
                    true)
                .getResponse()
                .getStatus())
        .isEqualTo(409);
    assertThat(balance(p)).isEqualByComparingTo("1");
    assertThat(
            db.queryForObject(
                "SELECT quantity FROM stock_movements WHERE id=?",
                BigDecimal.class,
                UUID.fromString(original)))
        .isEqualByComparingTo("1.5");
    assertThat(count("stock_movements")).isEqualTo(3);
    reconciles(p);
  }

  @Test
  void stateAndOwnershipAndCsrfAreEnforced() throws Exception {
    UUID w = work(appointment), p = part("A", "1");
    assertThat(
            request("POST", usePath(w), use(p, "1"), UUID.randomUUID(), admin, true)
                .getResponse()
                .getStatus())
        .isEqualTo(409);
    assertThat(
            request(
                    "POST",
                    usePath(w),
                    use(p, "1"),
                    UUID.randomUUID(),
                    "work-customer@example.com",
                    true)
                .getResponse()
                .getStatus())
        .isEqualTo(403);
    assertThat(
            request("POST", usePath(w), use(p, "1"), UUID.randomUUID(), admin, false)
                .getResponse()
                .getStatus())
        .isEqualTo(403);
    assertThat(
            request("POST", usePath(w), use(p, "1"), null, admin, true).getResponse().getStatus())
        .isEqualTo(400);
    assertThat(mvc.perform(get("/api/work-orders/" + w)).andReturn().getResponse().getStatus())
        .isEqualTo(401);
    assertThat(
            mvc.perform(get("/api/work-orders/" + w).with(user("work-other-admin@example.com")))
                .andReturn()
                .getResponse()
                .getStatus())
        .isEqualTo(404);
    var own =
        mvc.perform(get("/api/work-orders/" + w).with(user("work-customer@example.com")))
            .andReturn();
    assertThat(own.getResponse().getStatus()).isEqualTo(200);
    ok("PATCH", "/api/admin/work-orders/" + w + "/status", Map.of("status", "IN_PROGRESS"));
    assertThat(
            request(
                    "PATCH",
                    "/api/admin/work-orders/" + w + "/status",
                    Map.of("status", "COMPLETED"),
                    UUID.randomUUID(),
                    admin,
                    true)
                .getResponse()
                .getStatus())
        .isEqualTo(409);
    UUID item =
        db.queryForObject("SELECT id FROM work_order_items WHERE work_order_id=?", UUID.class, w);
    ok("PATCH", "/api/admin/work-orders/" + w + "/items/" + item, Map.of("done", true));
    ok("PATCH", "/api/admin/work-orders/" + w + "/status", Map.of("status", "COMPLETED"));
    assertThat(
            request(
                    "PATCH",
                    "/api/admin/work-orders/" + w + "/status",
                    Map.of("status", "IN_PROGRESS"),
                    UUID.randomUUID(),
                    admin,
                    true)
                .getResponse()
                .getStatus())
        .isEqualTo(409);
  }

  @Test
  void invalidQuantitiesDuplicatePartsAndForgedFieldsRejected() throws Exception {
    UUID w = running(), p = part("A", "2");
    for (String q : List.of("0", "-1", "0.0001", "100000000000"))
      assertThat(
              request("POST", usePath(w), use(p, q), UUID.randomUUID(), admin, true)
                  .getResponse()
                  .getStatus())
          .isEqualTo(400);
    assertThat(
            request(
                    "POST",
                    usePath(w),
                    Map.of(
                        "lines",
                        List.of(
                            Map.of("partId", p, "quantity", 1), Map.of("partId", p, "quantity", 1)),
                        "reason",
                        "dup"),
                    UUID.randomUUID(),
                    admin,
                    true)
                .getResponse()
                .getStatus())
        .isEqualTo(400);
    assertThat(
            request(
                    "POST",
                    usePath(w),
                    Map.of(
                        "lines",
                        List.of(Map.of("partId", p, "quantity", 1)),
                        "reason",
                        "x",
                        "balance",
                        100),
                    UUID.randomUUID(),
                    admin,
                    true)
                .getResponse()
                .getStatus())
        .isEqualTo(400);
  }

  List<Integer> race(Callable<Integer> a, Callable<Integer> b) throws Exception {
    var pool = Executors.newFixedThreadPool(2);
    var start = new CountDownLatch(1);
    try {
      var f1 =
          pool.submit(
              () -> {
                start.await();
                return a.call();
              });
      var f2 =
          pool.submit(
              () -> {
                start.await();
                return b.call();
              });
      start.countDown();
      return List.of(f1.get(20, TimeUnit.SECONDS), f2.get(20, TimeUnit.SECONDS));
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void simultaneousDifferentOrdersCannotOversell() throws Exception {
    UUID w = running(), w2 = work(appointment()), p = part("A", "1");
    ok("PATCH", "/api/admin/work-orders/" + w2 + "/status", Map.of("status", "IN_PROGRESS"));
    var statuses =
        race(
            () ->
                request("POST", usePath(w), use(p, "0.750"), UUID.randomUUID(), admin, true)
                    .getResponse()
                    .getStatus(),
            () ->
                request("POST", usePath(w2), use(p, "0.750"), UUID.randomUUID(), admin, true)
                    .getResponse()
                    .getStatus());
    assertThat(statuses).containsExactlyInAnyOrder(200, 409);
    assertThat(balance(p)).isEqualByComparingTo("0.250");
    reconciles(p);
  }

  @Test
  void simultaneousSameKeyAppliesOnce() throws Exception {
    UUID w = running(), p = part("A", "2"), key = UUID.randomUUID();
    Callable<Integer> call =
        () ->
            request("POST", usePath(w), use(p, "0.5"), key, admin, true).getResponse().getStatus();
    assertThat(race(call, call)).containsExactly(200, 200);
    assertThat(balance(p)).isEqualByComparingTo("1.5");
    reconciles(p);
  }

  @Test
  void concurrentPartialReturnsCannotExceedUse() throws Exception {
    UUID w = running(), p = part("A", "2");
    String original =
        ok("POST", usePath(w), use(p, "1")).get("movements").get(0).get("id").asText();
    Object body = Map.of("originalUseId", original, "quantity", "0.750", "reason", "실물 반환");
    Callable<Integer> call =
        () ->
            request(
                    "POST",
                    "/api/admin/work-orders/" + w + "/parts/return",
                    body,
                    UUID.randomUUID(),
                    admin,
                    true)
                .getResponse()
                .getStatus();
    assertThat(race(call, call)).containsExactlyInAnyOrder(200, 409);
    assertThat(balance(p)).isEqualByComparingTo("1.75");
    reconciles(p);
  }

  @Test
  void oppositePartOrderHasNoDeadlock() throws Exception {
    UUID w = running(), w2 = work(appointment()), p = part("A", "2"), p2 = part("B", "2");
    ok("PATCH", "/api/admin/work-orders/" + w2 + "/status", Map.of("status", "IN_PROGRESS"));
    var a = Map.of("partId", p, "quantity", "0.5");
    var b = Map.of("partId", p2, "quantity", "0.5");
    assertThat(
            race(
                () ->
                    request(
                            "POST",
                            usePath(w),
                            Map.of("lines", List.of(a, b), "reason", "x"),
                            UUID.randomUUID(),
                            admin,
                            true)
                        .getResponse()
                        .getStatus(),
                () ->
                    request(
                            "POST",
                            usePath(w2),
                            Map.of("lines", List.of(b, a), "reason", "x"),
                            UUID.randomUUID(),
                            admin,
                            true)
                        .getResponse()
                        .getStatus()))
        .containsExactly(200, 200);
    assertThat(balance(p)).isEqualByComparingTo("1");
    reconciles(p);
    reconciles(p2);
  }

  @Test
  void concurrentUseAndCancellationPreserveActualUsage() throws Exception {
    UUID w = running(), p = part("A", "2");
    var statuses =
        race(
            () ->
                request("POST", usePath(w), use(p, "1"), UUID.randomUUID(), admin, true)
                    .getResponse()
                    .getStatus(),
            () ->
                request(
                        "PATCH",
                        "/api/admin/work-orders/" + w + "/status",
                        Map.of("status", "CANCELLED", "reason", "요청 취소"),
                        UUID.randomUUID(),
                        admin,
                        true)
                    .getResponse()
                    .getStatus());
    assertThat(statuses.get(1)).isEqualTo(200);
    assertThat(statuses.get(0)).isIn(200, 409);
    assertThat(balance(p)).isEqualByComparingTo(statuses.get(0) == 200 ? "1" : "2");
    assertThat(
            db.queryForObject(
                "SELECT COUNT(*) FROM stock_movements WHERE kind='RETURN'", Integer.class))
        .isZero();
    reconciles(p);
  }

  @Test
  void returnUsesOriginalPriceAndInactivePartsCanBeReturned() throws Exception {
    UUID w = running(), p = part("A", "2");
    String original =
        ok("POST", usePath(w), use(p, "1")).get("movements").get(0).get("id").asText();
    ok(
        "PATCH",
        "/api/admin/parts/" + p,
        Map.of(
            "sku",
            "A",
            "name",
            "변경된 이름",
            "unit",
            "L",
            "minimumQuantity",
            0,
            "unitPrice",
            30000,
            "active",
            false));
    var back =
        ok(
            "POST",
            "/api/admin/work-orders/" + w + "/parts/return",
            Map.of("originalUseId", original, "quantity", "0.500", "reason", "실물 반환"));
    assertThat(back.get("movements").get(0).get("part_name").asText()).isEqualTo("A");
    assertThat(back.get("movements").get(0).get("unit_price").asInt()).isEqualTo(10000);
    assertThat(
            request("POST", usePath(w), use(p, "0.1"), UUID.randomUUID(), admin, true)
                .getResponse()
                .getStatus())
        .isEqualTo(409);
    assertThat(
            request(
                    "PATCH",
                    "/api/admin/parts/" + p,
                    Map.of(
                        "sku",
                        "A",
                        "name",
                        "A",
                        "unit",
                        "EA",
                        "minimumQuantity",
                        0,
                        "unitPrice",
                        30000,
                        "active",
                        true),
                    UUID.randomUUID(),
                    admin,
                    true)
                .getResponse()
                .getStatus())
        .isEqualTo(409);
    reconciles(p);
  }

  @Test
  void returnMustBelongToOrderAndCustomerCannotSeeWarehouseBalance() throws Exception {
    UUID w = running(), w2 = work(appointment()), p = part("A", "2");
    String original =
        ok("POST", usePath(w), use(p, "1")).get("movements").get(0).get("id").asText();
    assertThat(
            request(
                    "POST",
                    "/api/admin/work-orders/" + w2 + "/parts/return",
                    Map.of("originalUseId", original, "quantity", "0.5", "reason", "반환"),
                    UUID.randomUUID(),
                    admin,
                    true)
                .getResponse()
                .getStatus())
        .isEqualTo(404);
    var own =
        mvc.perform(get("/api/work-orders/" + w).with(user("work-customer@example.com")))
            .andReturn();
    var movement = json.readTree(own.getResponse().getContentAsString()).get("movements").get(0);
    assertThat(movement.has("balance_after")).isFalse();
    assertThat(movement.has("actor_id")).isFalse();
    reconciles(p);
  }

  @Test
  void recordedMileageCannotBeOverwrittenByCustomerUpdate() throws Exception {
    work(appointment);
    var req =
        put("/api/vehicles/" + car)
            .with(user("work-customer@example.com"))
            .with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content(
                json.writeValueAsString(new VehicleRequest("99가1234", "기아", "K3", 2024, 26000)));
    assertThat(mvc.perform(req).andReturn().getResponse().getStatus()).isEqualTo(409);
    assertThat(db.queryForObject("SELECT mileage FROM vehicles WHERE id=?", Integer.class, car))
        .isEqualTo(27000);
  }
}
