package com.pitflow;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

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
  record MechanicActor(String email, UUID userId, UUID mechanicId) {}

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
        """
        INSERT INTO service_items
        (id,name,description,labor_price,duration_minutes,active,created_at,updated_at)
        VALUES (?,?,?,?,?,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
        """,
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
    db.update("DELETE FROM notifications");
    db.update("DELETE FROM part_shortage_reports");
    // Disposable test DB only: production has no delete endpoints for these records.
    db.update("DELETE FROM inventory_cost_allocations");
    db.update("DELETE FROM inventory_cost_lots");
    db.update("DELETE FROM stock_movements WHERE kind='RETURN'");
    db.update("DELETE FROM stock_movements");
    db.update("DELETE FROM work_order_events");
    db.update("DELETE FROM work_order_items");
    db.update("DELETE FROM work_orders");
    db.update("DELETE FROM stock_operations");
    db.update("DELETE FROM mechanics");
    db.update("DELETE FROM appointment_item_parts");
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
        """
        INSERT INTO appointment_items
        (appointment_id, service_item_id, name, labor_price, duration_minutes)
        VALUES (?,?,?,?,?)
        """,
        id,
        serviceItem,
        "예약 당시 정비",
        20000,
        30);
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

  UUID mechanicAccount(String email, String code, boolean active) {
    UUID account =
        users
            .saveAndFlush(new AppUser(email, "test-hash", code, AppUser.Role.MECHANIC))
            .getId();
    UUID profile = UUID.randomUUID();
    db.update(
        "INSERT INTO mechanics (id,user_id,code,name,active) VALUES (?,?,?,?,?)",
        profile,
        account,
        code,
        code,
        active);
    return profile;
  }

  MechanicActor assignedMechanic(String email) {
    UUID account =
        users
            .saveAndFlush(new AppUser(email, "test-hash", "담당 정비사", AppUser.Role.MECHANIC))
            .getId();
    db.update("UPDATE mechanics SET user_id=? WHERE id=?", account, mechanic);
    return new MechanicActor(email, account, mechanic);
  }

  MvcResult mechanicRequest(String method, String path, Object body, UUID key, String email)
      throws Exception {
    MockHttpServletRequestBuilder req = "POST".equals(method) ? post(path) : patch(path);
    req.with(user(email).roles("MECHANIC")).with(csrf());
    if (key != null) req.header("Idempotency-Key", key.toString());
    return mvc.perform(
            req.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)))
        .andReturn();
  }

  JsonNode mechanicOk(String method, String path, Object body, UUID key, String email)
      throws Exception {
    var result = mechanicRequest(method, path, body, key, email);
    assertThat(result.getResponse().getStatus())
        .withFailMessage(result.getResponse().getContentAsString())
        .isEqualTo(200);
    return json.readTree(result.getResponse().getContentAsString());
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

  int notificationCount(UUID recipient, String type, UUID work) {
    return db.queryForObject(
        """
        SELECT COUNT(*)
        FROM notifications
        WHERE recipient_user_id=?
          AND type=?
          AND work_order_id=?
        """,
        Integer.class,
        recipient,
        type,
        work);
  }

  @Test
  void workAssignmentNotificationsCoverReceiveReassignAndSameMechanic() throws Exception {
    var actor = assignedMechanic("phase3b-assigned@example.com");

    UUID work = work(appointment);

    assertThat(notificationCount(actor.userId(), "WORK_ASSIGNED", work))
        .isEqualTo(1);

    ok(
        "PATCH",
        "/api/admin/work-orders/" + work + "/assignment",
        Map.of("mechanicId", actor.mechanicId()));

    assertThat(notificationCount(actor.userId(), "WORK_ASSIGNED", work))
        .isEqualTo(1);

    UUID replacement =
        mechanicAccount(
            "phase3b-replacement@example.com",
            "P3B-REPLACE",
            true);

    UUID replacementUser =
        db.queryForObject(
            "SELECT user_id FROM mechanics WHERE id=?",
            UUID.class,
            replacement);

    ok(
        "PATCH",
        "/api/admin/work-orders/" + work + "/assignment",
        Map.of("mechanicId", replacement));

    assertThat(notificationCount(replacementUser, "WORK_ASSIGNED", work))
        .isEqualTo(1);

    assertThat(notificationCount(actor.userId(), "WORK_ASSIGNED", work))
        .isEqualTo(1);
  }

  @Test
  void laterAssignmentNotifiesMechanicAndMechanicCompletionNotifiesAdmins() throws Exception {
    var actor = assignedMechanic("phase3b-complete@example.com");

    var receive = new HashMap<String, Object>();
    receive.put("appointmentId", appointment);
    receive.put("receivedMileage", 27000);
    receive.put("mechanicId", null);
    receive.put("notes", "미배정 입고");

    UUID work =
        UUID.fromString(
            ok(
                    "POST",
                    "/api/admin/work-orders/from-appointment",
                    receive)
                .get("id")
                .asText());

    assertThat(
            db.queryForObject(
                """
                SELECT COUNT(*)
                FROM notifications
                WHERE work_order_id=?
                  AND type='WORK_ASSIGNED'
                """,
                Integer.class,
                work))
        .isZero();

    ok(
        "PATCH",
        "/api/admin/work-orders/" + work + "/assignment",
        Map.of("mechanicId", actor.mechanicId()));

    assertThat(notificationCount(actor.userId(), "WORK_ASSIGNED", work))
        .isEqualTo(1);

    mechanicOk(
        "PATCH",
        "/api/mechanic/work-orders/" + work + "/status",
        Map.of("status", "IN_PROGRESS"),
        UUID.randomUUID(),
        actor.email());

    UUID item =
        db.queryForObject(
            "SELECT id FROM work_order_items WHERE work_order_id=?",
            UUID.class,
            work);

    mechanicOk(
        "PATCH",
        "/api/mechanic/work-orders/" + work + "/items/" + item,
        Map.of("done", true),
        UUID.randomUUID(),
        actor.email());

    mechanicOk(
        "PATCH",
        "/api/mechanic/work-orders/" + work + "/status",
        Map.of("status", "COMPLETED"),
        UUID.randomUUID(),
        actor.email());

    assertThat(
            db.queryForObject(
                """
                SELECT COUNT(*)
                FROM notifications n
                JOIN users u ON u.id=n.recipient_user_id
                WHERE n.work_order_id=?
                  AND n.type='WORK_COMPLETED'
                  AND u.role='ADMIN'
                """,
                Integer.class,
                work))
        .isEqualTo(2);

    assertThat(
            db.queryForObject(
                """
                SELECT COUNT(*)
                FROM notifications n
                JOIN users u ON u.id=n.recipient_user_id
                WHERE n.work_order_id=?
                  AND n.type='WORK_COMPLETED'
                  AND u.role<>'ADMIN'
                """,
                Integer.class,
                work))
        .isZero();
  }
  @Test
  void washerUsesActualFractionalQuantityOnlyOncePerWorkOrder() throws Exception {
    UUID washerService =
        UUID.fromString("f6b2e966-cf84-3576-9a3f-a64ebf1de473");

    db.update(
        """
        INSERT INTO service_items
          (id,name,description,labor_price,duration_minutes,active,created_at,updated_at)
        VALUES (?,?,?,?,?,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
        """,
        washerService,
        "워셔액 보충 서비스",
        "테스트",
        0,
        30,
        true);

    UUID w = running();
    db.update(
        "UPDATE work_order_items SET service_item_id=? WHERE work_order_id=?",
        washerService,
        w);

    UUID washer = part("PF-WASHER", "2");

    assertThat(
            request("POST", usePath(w), use(washer, "0.5"), UUID.randomUUID(), admin, true)
                .getResponse()
                .getStatus())
        .isEqualTo(200);
    assertThat(balance(washer)).isEqualByComparingTo("1.5");

    assertThat(
            request("POST", usePath(w), use(washer, "0.25"), UUID.randomUUID(), admin, true)
                .getResponse()
                .getStatus())
        .isEqualTo(409);

    assertThat(balance(washer)).isEqualByComparingTo("1.5");
    assertThat(
            db.queryForObject(
                "SELECT COUNT(*) FROM stock_movements WHERE work_order_id=? AND part_id=? AND kind='USE'",
                Integer.class,
                w,
                washer))
        .isEqualTo(1);
  }
  @Test
  void releaseRequiresCompletionAndPreservesOneEventUnderConcurrentRequests() throws Exception {
    UUID w = running();
    String path = "/api/admin/work-orders/" + w + "/release";
    assertThat(
            request("POST", path, Map.of(), UUID.randomUUID(), admin, true)
                .getResponse()
                .getStatus())
        .isEqualTo(409);
    UUID item =
        db.queryForObject("SELECT id FROM work_order_items WHERE work_order_id=?", UUID.class, w);
    ok("PATCH", "/api/admin/work-orders/" + w + "/items/" + item, Map.of("done", true));
    ok("PATCH", "/api/admin/work-orders/" + w + "/status", Map.of("status", "COMPLETED"));
    assertThat(
            request("POST", path, Map.of(), UUID.randomUUID(), "work-customer@example.com", true)
                .getResponse()
                .getStatus())
        .isEqualTo(403);
    assertThat(
            request("POST", path, Map.of(), UUID.randomUUID(), admin, false)
                .getResponse()
                .getStatus())
        .isEqualTo(403);
    UUID key = UUID.randomUUID();
    assertThat(
            race(
                () -> request("POST", path, Map.of(), key, admin, true).getResponse().getStatus(),
                () ->
                    request("POST", path, Map.of(), UUID.randomUUID(), admin, true)
                        .getResponse()
                        .getStatus()))
        .containsExactly(200, 200);
    assertThat(request("POST", path, Map.of(), key, admin, true).getResponse().getStatus())
        .isEqualTo(200);
    assertThat(
            db.queryForObject(
                "SELECT COUNT(*) FROM work_orders WHERE released_at IS NOT NULL", Integer.class))
        .isEqualTo(1);
    assertThat(
            db.queryForObject(
                "SELECT COUNT(*) FROM work_order_events WHERE event_type='RELEASED'",
                Integer.class))
        .isEqualTo(1);
    assertThat(db.queryForObject("SELECT status FROM work_orders WHERE id=?", String.class, w))
        .isEqualTo("COMPLETED");
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
  void receiveWithoutMechanicPreservesSnapshotsAndCanBeAssignedLater() throws Exception {
    var actor = assignedMechanic("phase2e-receive@example.com");
    db.update("UPDATE appointment_items SET quantity=2 WHERE appointment_id=?", appointment);
    var body = new HashMap<String, Object>();
    body.put("appointmentId", appointment);
    body.put("receivedMileage", 27000);
    body.put("mechanicId", null);
    body.put("notes", "미배정 입고");
    var received = ok("POST", "/api/admin/work-orders/from-appointment", body);
    UUID work = UUID.fromString(received.get("id").asText());
    assertThat(received.get("mechanic_id").isNull()).isTrue();
    assertThat(received.get("mechanic_name").isNull()).isTrue();
    assertThat(received.get("items").get(0).get("quantity").asInt()).isEqualTo(2);
    assertThat(received.get("events").get(0).get("detail").asText()).contains("미배정");
    assertThat(db.queryForObject("SELECT mileage FROM vehicles WHERE id=?", Integer.class, car))
        .isEqualTo(27000);
    mvc.perform(get("/api/mechanic/work-orders/" + work).with(user(actor.email()).roles("MECHANIC")))
        .andExpect(status().isNotFound());
    assertThat(
            mechanicRequest(
                    "PATCH",
                    "/api/mechanic/work-orders/" + work + "/status",
                    Map.of("status", "IN_PROGRESS"),
                    UUID.randomUUID(),
                    actor.email())
                .getResponse()
                .getStatus())
        .isEqualTo(404);
    ok(
        "PATCH",
        "/api/admin/work-orders/" + work + "/assignment",
        Map.of("mechanicId", actor.mechanicId()));
    mvc.perform(get("/api/mechanic/work-orders/" + work).with(user(actor.email()).roles("MECHANIC")))
        .andExpect(status().isOk());
  }

  @Test
  void receiveKeepsBookedQuantityAndMechanicUsesAggregatedPartSnapshot() throws Exception {
    var actor = assignedMechanic("snapshot-mechanic@example.com");
    UUID tire = part("SNAPSHOT-TIRE", "8");
    db.update(
        "INSERT INTO service_part_requirements"
            + " (service_id,part_id,required_quantity,quantity_confirmed) VALUES (?,?,1,TRUE)",
        serviceItem,
        tire);
    db.update(
        "UPDATE appointment_items SET quantity=4,parts_quote_captured=TRUE WHERE appointment_id=?",
        appointment);
    db.update(
        "INSERT INTO appointment_item_parts"
            + " (appointment_id,service_item_id,part_id,part_name,unit,required_quantity_per_service,total_quantity,unit_price,amount,charge_policy)"
            + " VALUES (?,?,?,?,?,1,4,10000,40000,'STANDARD')",
        appointment,
        serviceItem,
        tire,
        "예약 타이어",
        "L");

    UUID secondService = UUID.randomUUID();
    db.update(
        "INSERT INTO service_items"
            + " (id,name,description,labor_price,duration_minutes,active,created_at,updated_at)"
            + " VALUES (?,?,?,10000,30,TRUE,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
        secondService,
        "타이어 추가 점검",
        "설명");
    db.update(
        "INSERT INTO appointment_items"
            + " (appointment_id,service_item_id,name,labor_price,duration_minutes,quantity,parts_quote_captured)"
            + " VALUES (?,?,?,10000,30,2,TRUE)",
        appointment,
        secondService,
        "타이어 추가 점검");
    db.update(
        "INSERT INTO appointment_item_parts"
            + " (appointment_id,service_item_id,part_id,part_name,unit,required_quantity_per_service,total_quantity,unit_price,amount,charge_policy)"
            + " VALUES (?,?,?,?,?,1,2,10000,20000,'STANDARD')",
        appointment,
        secondService,
        tire,
        "예약 타이어",
        "L");

    UUID work = work(appointment);
    assertThat(
            db.queryForObject(
                "SELECT quantity FROM work_order_items WHERE work_order_id=? AND service_item_id=?",
                Integer.class,
                work,
                serviceItem))
        .isEqualTo(4);
    mvc.perform(get("/api/mechanic/work-orders/" + work).with(user(actor.email()).roles("MECHANIC")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.suggested_parts[0].id").value(tire.toString()))
        .andExpect(jsonPath("$.suggested_parts[0].required_quantity").value(6));
  }

  @Test
  void bulkCompleteKeepsSkippedItemsAndEnforcesMechanicOwnership() throws Exception {
    var actor = assignedMechanic("bulk-mechanic@example.com");
    UUID own = work(appointment);
    ok("PATCH", "/api/admin/work-orders/" + own + "/status", Map.of("status", "IN_PROGRESS"));
    UUID pending =
        db.queryForObject("SELECT id FROM work_order_items WHERE work_order_id=?", UUID.class, own);
    db.update("UPDATE work_order_items SET status='IN_PROGRESS' WHERE id=?", pending);
    UUID anotherPending = UUID.randomUUID();
    UUID waiting = UUID.randomUUID();
    UUID skipped = UUID.randomUUID();
    UUID pendingService = UUID.randomUUID();
    UUID waitingService = UUID.randomUUID();
    UUID skippedService = UUID.randomUUID();
    for (UUID extraService : List.of(pendingService, waitingService, skippedService)) {
      db.update(
          "INSERT INTO service_items"
              + " (id,name,description,labor_price,duration_minutes,active,created_at,updated_at)"
              + " VALUES (?,?,?,1000,30,TRUE,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
          extraService,
          "일괄 완료 테스트 " + extraService,
          "설명");
    }
    db.update(
        "INSERT INTO work_order_items"
            + " (id,work_order_id,service_item_id,name,labor_price,duration_minutes,status,done,quantity)"
            + " VALUES (?,?,?,?,?,?,'PENDING',FALSE,1)",
        anotherPending, own, pendingService, "대기 항목", 1000, 30);
    db.update(
        "INSERT INTO work_order_items"
            + " (id,work_order_id,service_item_id,name,labor_price,duration_minutes,status,done,quantity)"
            + " VALUES (?,?,?,?,?,?, 'WAITING_PARTS',FALSE,1)",
        waiting, own, waitingService, "대기 부품 항목", 1000, 30);
    db.update(
        "INSERT INTO work_order_items"
            + " (id,work_order_id,service_item_id,name,labor_price,duration_minutes,status,done,skip_reason,quantity)"
            + " VALUES (?,?,?,?,?,?,'SKIPPED',FALSE,'고객 제외',1)",
        skipped, own, skippedService, "건너뜀 항목", 1000, 30);

    String path = "/api/mechanic/work-orders/" + own + "/items/complete-all";
    UUID key = UUID.randomUUID();
    mechanicOk("POST", path, Map.of(), key, actor.email());
    mechanicOk("POST", path, Map.of(), key, actor.email());
    assertThat(db.queryForObject("SELECT status FROM work_order_items WHERE id=?", String.class, pending))
        .isEqualTo("COMPLETED");
    assertThat(db.queryForObject("SELECT status FROM work_order_items WHERE id=?", String.class, anotherPending))
        .isEqualTo("COMPLETED");
    assertThat(db.queryForObject("SELECT status FROM work_order_items WHERE id=?", String.class, waiting))
        .isEqualTo("WAITING_PARTS");
    assertThat(db.queryForObject("SELECT status FROM work_order_items WHERE id=?", String.class, skipped))
        .isEqualTo("SKIPPED");
    assertThat(
            db.queryForObject(
                "SELECT COUNT(*) FROM work_order_events WHERE work_order_id=? AND event_type='ITEM_BULK'",
                Integer.class,
                own))
        .isOne();

    UUID otherMechanic = mechanicAccount("bulk-other@example.com", "BULK-OTHER", true);
    UUID other = work(appointment());
    db.update("UPDATE work_orders SET mechanic_id=? WHERE id=?", otherMechanic, other);
    assertThat(
            mechanicRequest(
                    "POST",
                    "/api/mechanic/work-orders/" + other + "/items/complete-all",
                    Map.of(),
                    UUID.randomUUID(),
                    actor.email())
                .getResponse()
                .getStatus())
        .isEqualTo(404);
  }

  @Test
  void customerPartsGuideIsSafeAndExcludesInactiveParts() throws Exception {
    UUID visible = part("GUIDE-VISIBLE", "2");
    UUID hidden = part("GUIDE-HIDDEN", "3");
    db.update("UPDATE parts SET active=FALSE WHERE id=?", hidden);
    mvc.perform(get("/api/parts-guide").with(user("work-customer@example.com").roles("CUSTOMER")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[?(@.id == '" + visible + "')].sku").exists())
        .andExpect(jsonPath("$[?(@.id == '" + hidden + "')]").doesNotExist())
        .andExpect(jsonPath("$[0].quantity").doesNotExist())
        .andExpect(jsonPath("$[0].minimum_quantity").doesNotExist())
        .andExpect(jsonPath("$[0].unit_price").doesNotExist());
    mvc.perform(get("/api/parts-guide").with(user(admin).roles("ADMIN")))
        .andExpect(status().isForbidden());
  }

  @Test
  void mechanicPartPickerIsMinimalAndRoleProtected() throws Exception {
    var actor = assignedMechanic("phase2e-parts@example.com");
    UUID available = part("P2E-PART", "1");
    mvc.perform(get("/api/mechanic/parts").with(user(actor.email()).roles("MECHANIC")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[?(@.id == '" + available + "')].sku").exists())
        .andExpect(jsonPath("$[0].quantity").doesNotExist())
        .andExpect(jsonPath("$[0].unit_price").doesNotExist());
    mvc.perform(
            get("/api/mechanic/parts")
                .with(user("work-customer@example.com").roles("CUSTOMER")))
        .andExpect(status().isForbidden());
  }

  @Test
  void adminCanAssignReassignReadAndClearCurrentMechanic() throws Exception {
    UUID w = work(appointment);
    var unassigned = new HashMap<String, Object>();
    unassigned.put("mechanicId", null);
    var cleared =
        ok("PATCH", "/api/admin/work-orders/" + w + "/assignment", unassigned);
    assertThat(cleared.get("mechanic_id").isNull()).isTrue();
    assertThat(cleared.get("mechanic_name").isNull()).isTrue();

    var assigned =
        ok(
            "PATCH",
            "/api/admin/work-orders/" + w + "/assignment",
            Map.of("mechanicId", mechanic));
    assertThat(assigned.get("mechanic_id").asText()).isEqualTo(mechanic.toString());

    UUID other =
        UUID.fromString(
            ok(
                    "POST",
                    "/api/admin/mechanics",
                    Map.of("code", "M2", "name", "다른 정비사", "active", true))
                .get("id")
                .asText());
    var reassigned =
        ok(
            "PATCH",
            "/api/admin/work-orders/" + w + "/assignment",
            Map.of("mechanicId", other));
    assertThat(reassigned.get("mechanic_id").asText()).isEqualTo(other.toString());
    assertThat(reassigned.get("mechanic_name").asText()).isEqualTo("다른 정비사");

    mvc.perform(get("/api/admin/work-orders/" + w).with(user(admin).roles("ADMIN")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.mechanic_id").value(other.toString()))
        .andExpect(jsonPath("$.mechanic_name").value("다른 정비사"));
    ok(
        "PATCH",
        "/api/admin/mechanics/" + other,
        Map.of("code", "M2", "name", "다른 정비사", "active", false));
    mvc.perform(get("/api/admin/work-orders/" + w).with(user(admin).roles("ADMIN")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.mechanic_id").value(other.toString()))
        .andExpect(jsonPath("$.mechanic_name").value("다른 정비사"));
  }

  @Test
  void assignmentRejectsMissingInactiveAndNonAdminMechanics() throws Exception {
    UUID w = work(appointment);
    String path = "/api/admin/work-orders/" + w + "/assignment";
    assertThat(
            request(
                    "PATCH",
                    path,
                    Map.of("mechanicId", UUID.randomUUID()),
                    UUID.randomUUID(),
                    admin,
                    true)
                .getResponse()
                .getStatus())
        .isEqualTo(404);

    UUID inactive =
        UUID.fromString(
            ok(
                    "POST",
                    "/api/admin/mechanics",
                    Map.of("code", "M2", "name", "비활성 정비사", "active", false))
                .get("id")
                .asText());
    assertThat(
            request(
                    "PATCH",
                    path,
                    Map.of("mechanicId", inactive),
                    UUID.randomUUID(),
                    admin,
                    true)
                .getResponse()
                .getStatus())
        .isEqualTo(409);
    assertThat(
            request(
                    "PATCH",
                    path,
                    Map.of("mechanicId", mechanic),
                    UUID.randomUUID(),
                    "work-customer@example.com",
                    true)
                .getResponse()
                .getStatus())
        .isEqualTo(403);
    mvc.perform(
            patch(path)
                .with(user("phase2b-mechanic@example.com").roles("MECHANIC"))
                .with(csrf())
                .header("Idempotency-Key", UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("mechanicId", mechanic))))
        .andExpect(status().isForbidden());
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
  void partDescriptionCanBeSavedClearedAndIsAdminOnly() throws Exception {
    var created =
        ok(
            "POST",
            "/api/admin/parts",
            Map.of(
                "sku",
                "DESC-1",
                "name",
                "설명 테스트 부품",
                "description",
                "교환 전 규격을 확인하세요.\r\n서늘한 곳에 보관합니다.",
                "unit",
                "EA",
                "minimumQuantity",
                0,
                "unitPrice",
                15000,
                "active",
                true));
    UUID part = UUID.fromString(created.get("id").asText());
    assertThat(created.get("description").asText()).isEqualTo("교환 전 규격을 확인하세요.\n서늘한 곳에 보관합니다.");
    assertThat(balance(part)).isEqualByComparingTo("0");
    assertThat(
            db.queryForObject(
                "SELECT COUNT(*) FROM stock_movements WHERE part_id=?", Integer.class, part))
        .isZero();

    var cleared =
        ok(
            "PATCH",
            "/api/admin/parts/" + part,
            Map.of(
                "sku",
                "DESC-1",
                "name",
                "설명 테스트 부품",
                "description",
                "   ",
                "unit",
                "EA",
                "minimumQuantity",
                0,
                "unitPrice",
                15000,
                "active",
                true));
    assertThat(cleared.get("description").asText()).isEmpty();

    var tooLong =
        request(
            "PATCH",
            "/api/admin/parts/" + part,
            Map.of(
                "sku",
                "DESC-1",
                "name",
                "설명 테스트 부품",
                "description",
                "가".repeat(601),
                "unit",
                "EA",
                "minimumQuantity",
                0,
                "unitPrice",
                15000,
                "active",
                true),
            UUID.randomUUID(),
            admin,
            true);
    assertThat(tooLong.getResponse().getStatus()).isEqualTo(400);

    var customerAttempt =
        request(
            "PATCH",
            "/api/admin/parts/" + part,
            Map.of(
                "sku",
                "DESC-1",
                "name",
                "권한 없음",
                "description",
                "변경 시도",
                "unit",
                "EA",
                "minimumQuantity",
                0,
                "unitPrice",
                15000,
                "active",
                true),
            UUID.randomUUID(),
            "work-customer@example.com",
            true);
    assertThat(customerAttempt.getResponse().getStatus()).isEqualTo(403);
    assertThat(db.queryForObject("SELECT name FROM parts WHERE id=?", String.class, part))
        .isEqualTo("설명 테스트 부품");
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
    void mechanicCanReadOnlyOwnAssignedWorkOrders() throws Exception {
        UUID account =
            users
                .saveAndFlush(
                    new AppUser(
                        "assigned-mechanic@example.com", "test-hash", "담당 정비사", AppUser.Role.MECHANIC))
                .getId();
        db.update("UPDATE mechanics SET user_id=? WHERE id=?", account, mechanic);

        UUID suggestedPart = part("P2C-SUGGESTED", "1");
        db.update(
            """
            INSERT INTO service_part_requirements
            (service_id, part_id, required_quantity, quantity_confirmed)
            VALUES (?, ?, ?, ?)
            """,
            serviceItem,
            suggestedPart,
            new BigDecimal("1"),
            true);

        UUID own = work(appointment);

        UUID otherMechanic = mechanicAccount("other-mechanic@example.com", "P2C-OTHER", true);
        UUID other = work(appointment());
        db.update(
            "UPDATE work_orders SET mechanic_id=?,mechanic_name='다른 정비사' WHERE id=?",
            otherMechanic,
            other);
        UUID unassigned = work(appointment());
        db.update(
            "UPDATE work_orders SET mechanic_id=NULL,mechanic_name=NULL WHERE id=?", unassigned);

        var mechanicUser = user("assigned-mechanic@example.com").roles("MECHANIC");
        mvc.perform(get("/api/mechanic/work-orders").with(mechanicUser))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(1))
            .andExpect(jsonPath("$[0].id").value(own.toString()));

        mvc.perform(get("/api/mechanic/work-orders/" + own).with(mechanicUser))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value(own.toString()))
            .andExpect(jsonPath("$.events[0].actor_id").doesNotExist())
            .andExpect(jsonPath("$.suggested_parts.length()").value(1))
            .andExpect(jsonPath("$.suggested_parts[0].id").value(suggestedPart.toString()))
            .andExpect(jsonPath("$.suggested_parts[0].name").value("P2C-SUGGESTED"))
            .andExpect(jsonPath("$.suggested_parts[0].unit").value("L"))
            .andExpect(jsonPath("$.suggested_parts[0].quantity").doesNotExist())
            .andExpect(jsonPath("$.suggested_parts[0].unit_price").doesNotExist())
            .andExpect(jsonPath("$.suggested_parts[0].minimum_quantity").doesNotExist());

        mvc.perform(get("/api/mechanic/work-orders/" + other).with(mechanicUser))
            .andExpect(status().isNotFound());
        mvc.perform(get("/api/mechanic/work-orders/" + unassigned).with(mechanicUser))
            .andExpect(status().isNotFound());

        mvc.perform(
                patch("/api/admin/work-orders/" + own + "/status")
                    .with(mechanicUser)
                    .with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"status\":\"IN_PROGRESS\"}"))
            .andExpect(status().isForbidden());

        assertThat(db.queryForObject("SELECT status FROM work_orders WHERE id=?", String.class, own))
            .isEqualTo("RECEIVED");

        mvc.perform(get("/api/admin/work-orders").with(user(admin).roles("ADMIN")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(3));

        mvc.perform(get("/api/admin/work-orders/" + own).with(user(admin).roles("ADMIN")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.suggested_parts.length()").value(1))
            .andExpect(jsonPath("$.suggested_parts[0].id").value(suggestedPart.toString()))
            .andExpect(jsonPath("$.suggested_parts[0].quantity").exists());

        mvc.perform(
                get("/api/work-orders").with(user("work-customer@example.com").roles("CUSTOMER")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(3));

        mvc.perform(
                get("/api/work-orders/" + own)
                    .with(user("work-customer@example.com").roles("CUSTOMER")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value(own.toString()))
            .andExpect(jsonPath("$.suggested_parts").doesNotExist());
    }

  @Test
  void inactiveAndUnlinkedMechanicAccountsCannotReadWorkOrders() throws Exception {
    mechanicAccount("inactive-mechanic@example.com", "P2C-INACTIVE", false);
    users.saveAndFlush(
        new AppUser(
            "unlinked-mechanic@example.com", "test-hash", "미연결", AppUser.Role.MECHANIC));

    mvc.perform(
            get("/api/mechanic/work-orders")
                .with(user("inactive-mechanic@example.com").roles("MECHANIC")))
        .andExpect(status().isForbidden());
    mvc.perform(
            get("/api/mechanic/work-orders")
                .with(user("unlinked-mechanic@example.com").roles("MECHANIC")))
        .andExpect(status().isForbidden());
  }

  @Test
  void customerCannotUseMechanicWorkOrderEndpoints() throws Exception {
    mvc.perform(
            get("/api/mechanic/work-orders")
                .with(user("work-customer@example.com").roles("CUSTOMER")))
        .andExpect(status().isForbidden());
  }

  @Test
  void mechanicStateAndItemMutationsEnforceLockedOwnershipAndAuditUser() throws Exception {
    var actor = assignedMechanic("phase2d-mechanic@example.com");
    UUID own = work(appointment);
    UUID otherProfile = mechanicAccount("phase2d-other@example.com", "P2D-OTHER", true);
    UUID other = work(appointment());
    db.update("UPDATE work_orders SET mechanic_id=?,mechanic_name='다른 정비사' WHERE id=?", otherProfile, other);
    UUID unassigned = work(appointment());
    db.update("UPDATE work_orders SET mechanic_id=NULL,mechanic_name=NULL WHERE id=?", unassigned);

    mechanicOk(
        "PATCH",
        "/api/mechanic/work-orders/" + own + "/status",
        Map.of("status", "IN_PROGRESS"),
        UUID.randomUUID(),
        actor.email());
    assertThat(
            mechanicRequest(
                    "PATCH",
                    "/api/mechanic/work-orders/" + own + "/status",
                    Map.of("status", "RECEIVED"),
                    UUID.randomUUID(),
                    actor.email())
                .getResponse()
                .getStatus())
        .isEqualTo(409);
    assertThat(
            mechanicRequest(
                    "PATCH",
                    "/api/mechanic/work-orders/" + other + "/status",
                    Map.of("status", "IN_PROGRESS"),
                    UUID.randomUUID(),
                    actor.email())
                .getResponse()
                .getStatus())
        .isEqualTo(404);
    assertThat(
            mechanicRequest(
                    "PATCH",
                    "/api/mechanic/work-orders/" + unassigned + "/status",
                    Map.of("status", "IN_PROGRESS"),
                    UUID.randomUUID(),
                    actor.email())
                .getResponse()
                .getStatus())
        .isEqualTo(404);

    UUID ownItem =
        db.queryForObject("SELECT id FROM work_order_items WHERE work_order_id=?", UUID.class, own);
    UUID otherItem =
        db.queryForObject("SELECT id FROM work_order_items WHERE work_order_id=?", UUID.class, other);
    mechanicOk(
        "PATCH",
        "/api/mechanic/work-orders/" + own + "/items/" + ownItem,
        Map.of("done", true),
        UUID.randomUUID(),
        actor.email());
    assertThat(
            mechanicRequest(
                    "PATCH",
                    "/api/mechanic/work-orders/" + own + "/items/" + otherItem,
                    Map.of("done", true),
                    UUID.randomUUID(),
                    actor.email())
                .getResponse()
                .getStatus())
        .isEqualTo(404);
    mechanicOk(
        "PATCH",
        "/api/mechanic/work-orders/" + own + "/status",
        Map.of("status", "COMPLETED"),
        UUID.randomUUID(),
        actor.email());
    assertThat(
            mechanicRequest(
                    "PATCH",
                    "/api/mechanic/work-orders/" + own + "/items/" + ownItem,
                    Map.of("done", false),
                    UUID.randomUUID(),
                    actor.email())
                .getResponse()
                .getStatus())
        .isEqualTo(409);
    assertThat(
            db.queryForObject(
                "SELECT COUNT(*) FROM work_order_events WHERE work_order_id=? AND event_type IN ('STATUS','ITEM') AND actor_id=?",
                Integer.class,
                own,
                actor.userId()))
        .isEqualTo(3);

    db.update("UPDATE mechanics SET active=FALSE WHERE id=?", actor.mechanicId());
    assertThat(
            mechanicRequest(
                    "PATCH",
                    "/api/mechanic/work-orders/" + own + "/status",
                    Map.of("status", "CANCELLED", "reason", "차단 확인"),
                    UUID.randomUUID(),
                    actor.email())
                .getResponse()
                .getStatus())
        .isEqualTo(403);
    users.saveAndFlush(
        new AppUser("phase2d-unlinked@example.com", "test-hash", "미연결", AppUser.Role.MECHANIC));
    assertThat(
            mechanicRequest(
                    "PATCH",
                    "/api/mechanic/work-orders/" + own + "/status",
                    Map.of("status", "CANCELLED", "reason", "차단 확인"),
                    UUID.randomUUID(),
                    "phase2d-unlinked@example.com")
                .getResponse()
                .getStatus())
        .isEqualTo(403);
    mvc.perform(
            patch("/api/mechanic/work-orders/" + own + "/status")
                .with(user("work-customer@example.com").roles("CUSTOMER"))
                .with(csrf())
                .header("Idempotency-Key", UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"CANCELLED\",\"reason\":\"차단\"}"))
        .andExpect(status().isForbidden());
    mvc.perform(
            post("/api/mechanic/work-orders/" + own + "/release")
                .with(user(actor.email()).roles("MECHANIC"))
                .with(csrf())
                .header("Idempotency-Key", UUID.randomUUID()))
        .andExpect(status().isNotFound());
  }

  @Test
  void mechanicUsePreservesInventoryRulesIdempotencyAndCurrentAssignment() throws Exception {
    var actor = assignedMechanic("phase2d-use@example.com");
    UUID own = running();
    UUID ea =
        UUID.fromString(
            ok(
                    "POST",
                    "/api/admin/parts",
                    Map.of(
                        "sku", "P2D-EA",
                        "name", "P2D-EA",
                        "unit", "EA",
                        "minimumQuantity", 0,
                        "unitPrice", 10000,
                        "active", true))
                .get("id")
                .asText());
    ok(
        "POST",
        "/api/admin/parts/" + ea + "/receipts",
        Map.of("quantity", "3", "reason", "실물 입고"));
    String path = "/api/mechanic/work-orders/" + own + "/parts/use";
    UUID key = UUID.randomUUID();
    Object one = use(ea, "1");
    mechanicOk("POST", path, one, key, actor.email());
    mechanicOk("POST", path, one, key, actor.email());
    assertThat(balance(ea)).isEqualByComparingTo("2");
    assertThat(
            db.queryForObject(
                "SELECT COUNT(*) FROM stock_movements WHERE work_order_id=? AND part_id=? AND kind='USE' AND actor_id=?",
                Integer.class,
                own,
                ea,
                actor.userId()))
        .isEqualTo(1);
    assertThat(
            mechanicRequest("POST", path, use(ea, "2"), key, actor.email())
                .getResponse()
                .getStatus())
        .isEqualTo(409);
    assertThat(
            mechanicRequest("POST", path, use(ea, "0.5"), UUID.randomUUID(), actor.email())
                .getResponse()
                .getStatus())
        .isEqualTo(400);
    assertThat(
            mechanicRequest("POST", path, use(ea, "3"), UUID.randomUUID(), actor.email())
                .getResponse()
                .getStatus())
        .isEqualTo(409);

    UUID otherProfile = mechanicAccount("phase2d-use-other@example.com", "P2D-USE-OTHER", true);
    db.update("UPDATE work_orders SET mechanic_id=?,mechanic_name='다른 정비사' WHERE id=?", otherProfile, own);
    assertThat(
            mechanicRequest("POST", path, one, UUID.randomUUID(), actor.email())
                .getResponse()
                .getStatus())
        .isEqualTo(404);
    assertThat(
            mechanicRequest("POST", path, one, key, actor.email()).getResponse().getStatus())
        .isEqualTo(404);

    UUID unassigned = work(appointment());
    ok(
        "PATCH",
        "/api/admin/work-orders/" + unassigned + "/status",
        Map.of("status", "IN_PROGRESS"));
    db.update("UPDATE work_orders SET mechanic_id=NULL,mechanic_name=NULL WHERE id=?", unassigned);
    assertThat(
            mechanicRequest(
                    "POST",
                    "/api/mechanic/work-orders/" + unassigned + "/parts/use",
                    one,
                    UUID.randomUUID(),
                    actor.email())
                .getResponse()
                .getStatus())
        .isEqualTo(404);
    assertThat(balance(ea)).isEqualByComparingTo("2");
  }

  @Test
  void mechanicReturnPreservesOriginalUseNetQuantityAuditAndReplay() throws Exception {
    var actor = assignedMechanic("phase2d-return@example.com");
    UUID own = running();
    UUID liquid = part("P2D-L", "2");
    mechanicOk(
        "POST",
        "/api/mechanic/work-orders/" + own + "/parts/use",
        use(liquid, "1"),
        UUID.randomUUID(),
        actor.email());
    UUID original =
        db.queryForObject(
            "SELECT id FROM stock_movements WHERE work_order_id=? AND part_id=? AND kind='USE'",
            UUID.class,
            own,
            liquid);
    String path = "/api/mechanic/work-orders/" + own + "/parts/return";
    UUID key = UUID.randomUUID();
    Object half = Map.of("originalUseId", original, "quantity", "0.5", "reason", "실물 반환");
    mechanicOk("POST", path, half, key, actor.email());
    mechanicOk("POST", path, half, key, actor.email());
    assertThat(balance(liquid)).isEqualByComparingTo("1.5");
    UUID returnedOriginal =
        db.queryForObject(
            "SELECT original_use_id FROM stock_movements WHERE work_order_id=? AND kind='RETURN'",
            UUID.class,
            own);
    assertThat(returnedOriginal).isEqualTo(original);
    assertThat(
            db.queryForObject(
                "SELECT actor_id FROM stock_movements WHERE work_order_id=? AND kind='RETURN'",
                UUID.class,
                own))
        .isEqualTo(actor.userId());
    assertThat(
            mechanicRequest(
                    "POST",
                    path,
                    Map.of("originalUseId", original, "quantity", "0.25", "reason", "다른 payload"),
                    key,
                    actor.email())
                .getResponse()
                .getStatus())
        .isEqualTo(409);
    assertThat(
            mechanicRequest(
                    "POST",
                    path,
                    Map.of("originalUseId", original, "quantity", "0.75", "reason", "초과"),
                    UUID.randomUUID(),
                    actor.email())
                .getResponse()
                .getStatus())
        .isEqualTo(409);

    UUID another = work(appointment());
    ok("PATCH", "/api/admin/work-orders/" + another + "/status", Map.of("status", "IN_PROGRESS"));
    assertThat(
            mechanicRequest(
                    "POST",
                    "/api/mechanic/work-orders/" + another + "/parts/return",
                    half,
                    UUID.randomUUID(),
                    actor.email())
                .getResponse()
                .getStatus())
        .isEqualTo(404);
    UUID otherProfile = mechanicAccount("phase2d-return-other@example.com", "P2D-RETURN-OTHER", true);
    db.update("UPDATE work_orders SET mechanic_id=?,mechanic_name='다른 정비사' WHERE id=?", otherProfile, own);
    assertThat(
            mechanicRequest("POST", path, half, UUID.randomUUID(), actor.email())
                .getResponse()
                .getStatus())
        .isEqualTo(404);
    db.update("UPDATE work_orders SET mechanic_id=NULL,mechanic_name=NULL WHERE id=?", another);
    assertThat(
            mechanicRequest(
                    "POST",
                    "/api/mechanic/work-orders/" + another + "/parts/return",
                    half,
                    UUID.randomUUID(),
                    actor.email())
                .getResponse()
                .getStatus())
        .isEqualTo(404);
    assertThat(balance(liquid)).isEqualByComparingTo("1.5");
  }

  @Test
  void mechanicUseAndAdminReassignmentSerializeOnWorkOrderLock() throws Exception {
    var actor = assignedMechanic("phase2d-race@example.com");
    UUID work = running();
    UUID part = part("P2D-RACE", "1");
    UUID replacement = mechanicAccount("phase2d-replacement@example.com", "P2D-REPLACE", true);
    var statuses =
        race(
            () ->
                mechanicRequest(
                        "POST",
                        "/api/mechanic/work-orders/" + work + "/parts/use",
                        use(part, "1"),
                        UUID.randomUUID(),
                        actor.email())
                    .getResponse()
                    .getStatus(),
            () ->
                request(
                        "PATCH",
                        "/api/admin/work-orders/" + work + "/assignment",
                        Map.of("mechanicId", replacement),
                        UUID.randomUUID(),
                        admin,
                        true)
                    .getResponse()
                    .getStatus());
    assertThat(statuses.get(1)).isEqualTo(200);
    assertThat(statuses.get(0)).isIn(200, 404);
    assertThat(balance(part)).isEqualByComparingTo(statuses.get(0) == 200 ? "0" : "1");
    assertThat(
            db.queryForObject(
                "SELECT mechanic_id FROM work_orders WHERE id=?", UUID.class, work))
        .isEqualTo(replacement);
  }

  @Test
    void eaPartsRejectFractionalUseButAllowWholeQuantity() throws Exception {
    UUID w = running();

    UUID p =
        UUID.fromString(
            ok(
                    "POST",
                    "/api/admin/parts",
                    Map.of(
                        "sku", "EA-TEST",
                        "name", "EA 테스트 부품",
                        "unit", "EA",
                        "minimumQuantity", "1",
                        "unitPrice", 10000,
                        "active", true))
                .get("id")
                .asText());

    ok(
        "POST",
        "/api/admin/parts/" + p + "/receipts",
        Map.of(
            "quantity", "4",
            "reason", "테스트 입고"));

    assertThat(
            request(
                    "POST",
                    usePath(w),
                    Map.of(
                        "lines",
                        List.of(
                            Map.of(
                                "partId", p,
                                "quantity", "1.999")),
                        "reason",
                        "소수 EA 테스트"),
                    UUID.randomUUID(),
                    admin,
                    true)
                .getResponse()
                .getStatus())
        .isEqualTo(400);

    assertThat(balance(p)).isEqualByComparingTo("4");

    assertThat(
            request(
                    "POST",
                    usePath(w),
                    Map.of(
                        "lines",
                        List.of(
                            Map.of(
                                "partId", p,
                                "quantity", "2")),
                        "reason",
                        "정상 EA 테스트"),
                    UUID.randomUUID(),
                    admin,
                    true)
                .getResponse()
                .getStatus())
        .isEqualTo(200);

    assertThat(balance(p)).isEqualByComparingTo("2");
    }

  @Test
  void mechanicShortageReportsAreOwnedNotifiedResolvedAndReopenable() throws Exception {
    var actor = assignedMechanic("phase3c-mechanic@example.com");
    UUID own = work(appointment);
    UUID item = db.queryForObject("SELECT id FROM work_order_items WHERE work_order_id=?", UUID.class, own);
    UUID part = part("P3C-SHORT", "0");
    BigDecimal beforeQuantity = balance(part);
    int beforeMovements = count("stock_movements");

    var created =
        mechanicOk(
            "POST",
            "/api/mechanic/work-orders/" + own + "/shortages",
            Map.of("workOrderItemId", item, "partId", part, "requestedQuantity", "2.000", "reason", "재고 부족"),
            UUID.randomUUID(),
            actor.email());
    UUID report = UUID.fromString(created.get("id").asText());
    assertThat(created.get("status").asText()).isEqualTo("OPEN");
    assertThat(created.get("available_quantity_snapshot")).isNull();
    assertThat(
            db.queryForObject(
                "SELECT available_quantity_snapshot FROM part_shortage_reports WHERE id=?",
                BigDecimal.class,
                report))
        .isEqualByComparingTo("0");
    assertThat(balance(part)).isEqualByComparingTo(beforeQuantity);
    assertThat(count("stock_movements")).isEqualTo(beforeMovements);
    assertThat(notificationCount(users.findByEmail(admin).orElseThrow().getId(), "PART_SHORTAGE", own))
        .isEqualTo(1);
    assertThat(notificationCount(users.findByEmail("work-other-admin@example.com").orElseThrow().getId(), "PART_SHORTAGE", own))
        .isEqualTo(1);

    assertThat(
            mechanicRequest(
                    "POST",
                    "/api/mechanic/work-orders/" + own + "/shortages",
                    Map.of("workOrderItemId", item, "partId", part, "requestedQuantity", "2"),
                    UUID.randomUUID(),
                    actor.email())
                .getResponse()
                .getStatus())
        .isEqualTo(409);
    assertThat(
            mechanicRequest(
                    "POST",
                    "/api/mechanic/work-orders/" + own + "/shortages",
                    Map.of("workOrderItemId", item, "partId", part, "requestedQuantity", "0"),
                    UUID.randomUUID(),
                    actor.email())
                .getResponse()
                .getStatus())
        .isEqualTo(400);

    UUID otherWork = work(appointment());
    db.update("UPDATE work_orders SET mechanic_id=NULL,mechanic_name=NULL WHERE id=?", otherWork);
    UUID otherItem = db.queryForObject("SELECT id FROM work_order_items WHERE work_order_id=?", UUID.class, otherWork);
    assertThat(
            mechanicRequest(
                    "POST",
                    "/api/mechanic/work-orders/" + otherWork + "/shortages",
                    Map.of("workOrderItemId", otherItem, "partId", part, "requestedQuantity", "1"),
                    UUID.randomUUID(),
                    actor.email())
                .getResponse()
                .getStatus())
        .isEqualTo(404);

    mvc.perform(get("/api/admin/part-shortages").with(user(admin).roles("ADMIN")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].id").value(report.toString()))
        .andExpect(jsonPath("$[0].part_name").value("P3C-SHORT"));
    mvc.perform(get("/api/admin/part-shortages").with(user("work-customer@example.com").roles("CUSTOMER")))
        .andExpect(status().isForbidden());
    mvc.perform(get("/api/admin/part-shortages").with(user(actor.email()).roles("MECHANIC")))
        .andExpect(status().isForbidden());

    var resolved =
        ok("PATCH", "/api/admin/part-shortages/" + report + "/resolve", Map.of());
    assertThat(resolved.get("status").asText()).isEqualTo("RESOLVED");
    assertThat(resolved.get("resolved_at").isTextual()).isTrue();
    assertThat(resolved.get("resolved_by_user_id").asText())
        .isEqualTo(users.findByEmail(admin).orElseThrow().getId().toString());
    assertThat(balance(part)).isEqualByComparingTo(beforeQuantity);
    assertThat(count("stock_movements")).isEqualTo(beforeMovements);

    var reopened =
        mechanicOk(
            "POST",
            "/api/mechanic/work-orders/" + own + "/shortages",
            Map.of("workOrderItemId", item, "partId", part, "requestedQuantity", "1"),
            UUID.randomUUID(),
            actor.email());
    assertThat(reopened.get("id").asText()).isNotEqualTo(report.toString());
    assertThat(db.queryForObject("SELECT COUNT(*) FROM part_shortage_reports", Integer.class)).isEqualTo(2);
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

  @Test
  void itemStatusTransitionsKeepLegacyDoneCompatibleAndRequireExclusivePayload() throws Exception {
    UUID w = running();
    UUID item = db.queryForObject("SELECT id FROM work_order_items WHERE work_order_id=?", UUID.class, w);

    assertThat(
            request("PATCH", "/api/admin/work-orders/" + w + "/items/" + item,
                    Map.of("status", "COMPLETED", "done", true), UUID.randomUUID(), admin, true)
                .getResponse().getStatus())
        .isEqualTo(400);
    assertThat(
            request("PATCH", "/api/admin/work-orders/" + w + "/items/" + item,
                    Map.of(), UUID.randomUUID(), admin, true)
                .getResponse().getStatus())
        .isEqualTo(400);

    ok("PATCH", "/api/admin/work-orders/" + w + "/items/" + item, Map.of("done", true));
    assertThat(db.queryForObject("SELECT status FROM work_order_items WHERE id=?", String.class, item))
        .isEqualTo("COMPLETED");
    ok("PATCH", "/api/admin/work-orders/" + w + "/items/" + item, Map.of("done", false));
    assertThat(db.queryForObject("SELECT status FROM work_order_items WHERE id=?", String.class, item))
        .isEqualTo("IN_PROGRESS");
    int events = db.queryForObject("SELECT COUNT(*) FROM work_order_events WHERE work_order_id=?", Integer.class, w);
    ok("PATCH", "/api/admin/work-orders/" + w + "/items/" + item, Map.of("done", false));
    assertThat(db.queryForObject("SELECT COUNT(*) FROM work_order_events WHERE work_order_id=?", Integer.class, w))
        .isEqualTo(events);
  }

  @Test
  void waitingPartsAndSkippedItemsBlockOrPermitWorkCompletionAsDefined() throws Exception {
    UUID w = running();
    UUID item = db.queryForObject("SELECT id FROM work_order_items WHERE work_order_id=?", UUID.class, w);
    ok("PATCH", "/api/admin/work-orders/" + w + "/items/" + item, Map.of("status", "WAITING_PARTS"));
    assertThat(
            request("PATCH", "/api/admin/work-orders/" + w + "/status",
                    Map.of("status", "COMPLETED"), UUID.randomUUID(), admin, true)
                .getResponse().getStatus())
        .isEqualTo(409);
    assertThat(
            request("PATCH", "/api/admin/work-orders/" + w + "/items/" + item,
                    Map.of("status", "SKIPPED"), UUID.randomUUID(), admin, true)
                .getResponse().getStatus())
        .isEqualTo(400);
    ok("PATCH", "/api/admin/work-orders/" + w + "/items/" + item,
        Map.of("status", "SKIPPED", "reason", "고객 승인 대기"));
    assertThat(db.queryForObject("SELECT done FROM work_order_items WHERE id=?", Boolean.class, item))
        .isFalse();
    ok("PATCH", "/api/admin/work-orders/" + w + "/items/" + item, Map.of("status", "IN_PROGRESS"));
    assertThat(db.queryForObject("SELECT skip_reason FROM work_order_items WHERE id=?", String.class, item))
        .isNull();
    ok("PATCH", "/api/admin/work-orders/" + w + "/items/" + item,
        Map.of("status", "SKIPPED", "reason", "고객 미승인"));
    ok("PATCH", "/api/admin/work-orders/" + w + "/status", Map.of("status", "COMPLETED"));
  }
}
