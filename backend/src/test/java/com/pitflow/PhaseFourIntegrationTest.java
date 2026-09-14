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
class PhaseFourIntegrationTest {
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
    db.update("DELETE FROM payment_records WHERE kind='REVERSAL'");
    db.update("DELETE FROM payment_records");
    db.update("DELETE FROM invoice_items");
    db.update("DELETE FROM invoices");
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

  JsonNode read(String path, String email) throws Exception {
    var r =
        mvc.perform(
                get(path).with(user(email).roles(email.contains("admin") ? "ADMIN" : "CUSTOMER")))
            .andReturn();
    assertThat(r.getResponse().getStatus())
        .withFailMessage(r.getResponse().getContentAsString())
        .isEqualTo(200);
    return json.readTree(r.getResponse().getContentAsString());
  }

  void complete(UUID w) throws Exception {
    UUID item =
        db.queryForObject("SELECT id FROM work_order_items WHERE work_order_id=?", UUID.class, w);
    ok("PATCH", "/api/admin/work-orders/" + w + "/items/" + item, Map.of("done", true));
    ok("PATCH", "/api/admin/work-orders/" + w + "/status", Map.of("status", "COMPLETED"));
  }

  String issuePath(UUID w) {
    return "/api/admin/billing/work-orders/" + w + "/invoices";
  }

  Map<String, Object> issueBody(UUID w) throws Exception {
    return Map.of(
        "expectedFingerprint",
        read("/api/admin/billing/work-orders/" + w + "/preview", admin).get("fingerprint").asText(),
        "confirmZeroPrices",
        true);
  }

  String invoice(UUID w) throws Exception {
    return ok("POST", issuePath(w), issueBody(w)).get("id").asText();
  }

  String payPath(String i) {
    return "/api/admin/billing/invoices/" + i + "/payments";
  }

  Object payment(int amount) {
    return Map.of("method", "CARD", "reference", "test", "expectedTotal", amount);
  }

  int status(String path, Object body, UUID key) throws Exception {
    return request("POST", path, body, key, admin, true).getResponse().getStatus();
  }

  @Test
  void snapshotsFractionalAmountsAndReplaysOnlyIdenticalCommands() throws Exception {
    UUID w = running(), p = part("OIL", "2");
    ok("POST", usePath(w), use(p, "0.125"));
    complete(w);
    UUID key = UUID.randomUUID();
    var body = issueBody(w);
    var first = request("POST", issuePath(w), body, key, admin, true);
    assertThat(first.getResponse().getStatus()).isEqualTo(200);
    var issued = json.readTree(first.getResponse().getContentAsString());
    assertThat(issued.get("total").decimalValue()).isEqualByComparingTo("21250");
    assertThat(
            json.readTree(
                request("POST", issuePath(w), body, key, admin, true)
                    .getResponse()
                    .getContentAsString()))
        .isEqualTo(issued);
    assertThat(status(issuePath(w), body, UUID.randomUUID())).isEqualTo(409);
    assertThat(
            request("POST", issuePath(w), body, key, "work-other-admin@example.com", true)
                .getResponse()
                .getStatus())
        .isEqualTo(409);
    db.update("UPDATE parts SET name='changed',unit_price=999 WHERE id=?", p);
    assertThat(
            read("/api/admin/billing/invoices/" + issued.get("id").asText(), admin)
                .get("stale")
                .asBoolean())
        .isFalse();
    assertThat(count("invoice_items")).isEqualTo(2);
  }

  @Test
  void zeroPriceRequiresConfirmationAndFailureRollsBackInvoiceAndKey() throws Exception {
    UUID w = running();
    complete(w);
    db.update("UPDATE work_order_items SET labor_price=0 WHERE work_order_id=?", w);
    var body = new HashMap<>(issueBody(w));
    body.put("confirmZeroPrices", false);
    UUID key = UUID.randomUUID();
    assertThat(status(issuePath(w), body, key)).isEqualTo(409);
    assertThat(count("invoices")).isZero();
    body.put("confirmZeroPrices", true);
    db.execute("ALTER TABLE invoice_items ADD CONSTRAINT test_no_invoice CHECK (amount>0)");
    try {
      assertThat(status(issuePath(w), body, key)).isEqualTo(409);
      assertThat(count("invoices")).isZero();
      assertThat(
              db.queryForObject(
                  "SELECT COUNT(*) FROM stock_operations WHERE id=?", Integer.class, key))
          .isZero();
    } finally {
      db.execute("ALTER TABLE invoice_items DROP CONSTRAINT test_no_invoice");
    }
    assertThat(status(issuePath(w), body, key)).isEqualTo(200);
    String i = db.queryForObject("SELECT id FROM invoices", UUID.class).toString();
    assertThat(status(payPath(i), payment(0), UUID.randomUUID())).isEqualTo(409);
  }

  @Test
  void paymentReversalAndVoidPreserveLedgerAndPreventDuplicates() throws Exception {
    UUID w = running();
    complete(w);
    String i = invoice(w);
    UUID key = UUID.randomUUID();
    assertThat(status(payPath(i), payment(19999), key)).isEqualTo(409);
    assertThat(status(payPath(i), payment(20000), key)).isEqualTo(200);
    assertThat(status(payPath(i), payment(20000), key)).isEqualTo(200);
    assertThat(status(payPath(i), payment(20000), UUID.randomUUID())).isEqualTo(409);
    String cancel = "/api/admin/billing/invoices/" + i + "/void";
    assertThat(status(cancel, Map.of("reason", "정정"), UUID.randomUUID())).isEqualTo(409);
    String p = db.queryForObject("SELECT id FROM payment_records", UUID.class).toString();
    String reverse = "/api/admin/billing/payments/" + p + "/reverse";
    key = UUID.randomUUID();
    assertThat(status(reverse, Map.of("reason", "수납 취소"), key)).isEqualTo(200);
    assertThat(status(reverse, Map.of("reason", "수납 취소"), key)).isEqualTo(200);
    assertThat(status(reverse, Map.of("reason", "수납 취소"), UUID.randomUUID())).isEqualTo(409);
    assertThat(status(cancel, Map.of("reason", "정정"), UUID.randomUUID())).isEqualTo(200);
    assertThat(count("payment_records")).isEqualTo(2);
    assertThat(count("invoice_items")).isEqualTo(1);
    assertThat(invoice(w)).isNotEqualTo(i);
  }

  @Test
  void returnInvalidatesQuoteAndIssuedSnapshotWithoutRewritingIt() throws Exception {
    UUID w = running(), p = part("A", "2");
    String useId = ok("POST", usePath(w), use(p, "1")).get("movements").get(0).get("id").asText();
    complete(w);
    var oldBody = issueBody(w);
    String i = invoice(w);
    ok(
        "POST",
        "/api/admin/work-orders/" + w + "/parts/return",
        Map.of("originalUseId", useId, "quantity", "0.5", "reason", "실물 반환"));
    assertThat(read("/api/admin/billing/invoices/" + i, admin).get("stale").asBoolean()).isTrue();
    assertThat(status(payPath(i), payment(30000), UUID.randomUUID())).isEqualTo(409);
    ok("POST", "/api/admin/billing/invoices/" + i + "/void", Map.of("reason", "반환 반영"));
    assertThat(status(issuePath(w), oldBody, UUID.randomUUID())).isEqualTo(409);
    String next = invoice(w);
    assertThat(read("/api/admin/billing/invoices/" + next, admin).get("total").asInt())
        .isEqualTo(25000);
    assertThat(read("/api/admin/billing/invoices/" + i, admin).get("total").asInt())
        .isEqualTo(30000);
    reconciles(p);
  }

  @Test
  void customerOwnershipAdminAndCsrfAndSummaryAreEnforced() throws Exception {
    UUID w = running();
    complete(w);
    String i = invoice(w);
    assertThat(
            read("/api/billing/history?vehicleId=" + car, "work-customer@example.com")
                .get(0)
                .has("customer_id"))
        .isFalse();
    assertThat(read("/api/billing/invoices/" + i, "work-customer@example.com").has("source_hash"))
        .isFalse();
    assertThat(
            mvc.perform(
                    get("/api/billing/invoices/" + i).with(user("work-other-admin@example.com")))
                .andReturn()
                .getResponse()
                .getStatus())
        .isEqualTo(404);
    assertThat(
            request("POST", payPath(i), payment(20000), UUID.randomUUID(), admin, false)
                .getResponse()
                .getStatus())
        .isEqualTo(403);
    assertThat(
            request(
                    "POST",
                    payPath(i),
                    payment(20000),
                    UUID.randomUUID(),
                    "work-customer@example.com",
                    true)
                .getResponse()
                .getStatus())
        .isEqualTo(403);
    assertThat(
            request("POST", payPath(i), payment(20000), null, admin, true)
                .getResponse()
                .getStatus())
        .isEqualTo(400);
    ok("POST", payPath(i), payment(20000));
    var day = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Seoul"));
    var summary = read("/api/admin/billing/summary?from=" + day + "&to=" + day, admin);
    assertThat(summary.get("received").asInt()).isEqualTo(20000);
    assertThat(summary.get("completed_count").asInt()).isEqualTo(1);
    assertThat(
            mvc.perform(
                    get("/api/admin/billing/summary?from=" + day + "&to=" + day)
                        .with(user("work-customer@example.com")))
                .andReturn()
                .getResponse()
                .getStatus())
        .isEqualTo(403);
  }

  List<Integer> race(Callable<Integer> call) throws Exception {
    var pool = Executors.newFixedThreadPool(2);
    var gate = new CountDownLatch(1);
    try {
      Callable<Integer> task =
          () -> {
            gate.await();
            return call.call();
          };
      var a = pool.submit(task);
      var b = pool.submit(task);
      gate.countDown();
      return List.of(a.get(20, TimeUnit.SECONDS), b.get(20, TimeUnit.SECONDS));
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void concurrentIssueAndPaymentSerializeAndSameKeyReplays() throws Exception {
    UUID w = running();
    complete(w);
    var body = issueBody(w);
    assertThat(race(() -> status(issuePath(w), body, UUID.randomUUID())))
        .containsExactlyInAnyOrder(200, 409);
    String i = db.queryForObject("SELECT id FROM invoices", UUID.class).toString();
    assertThat(race(() -> status(payPath(i), payment(20000), UUID.randomUUID())))
        .containsExactlyInAnyOrder(200, 409);
    assertThat(count("payment_records")).isEqualTo(1);
    String p = db.queryForObject("SELECT id FROM payment_records", UUID.class).toString();
    UUID key = UUID.randomUUID();
    assertThat(
            race(
                () ->
                    status(
                        "/api/admin/billing/payments/" + p + "/reverse",
                        Map.of("reason", "정정"),
                        key)))
        .containsExactly(200, 200);
    assertThat(count("payment_records")).isEqualTo(2);
  }
}
