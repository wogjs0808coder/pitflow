package com.pitflow;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.*;
import com.pitflow.user.*;
import com.pitflow.vehicle.*;
import com.pitflow.billing.TossPaymentGateway;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PhaseFourIntegrationTest {
  @Autowired MockMvc mvc;
  @Autowired ObjectMapper json;
  @Autowired JdbcTemplate db;
  @Autowired UserRepository users;
  @Autowired VehicleRepository vehicles;
  @MockitoBean TossPaymentGateway toss;
  UUID car, customer, mechanic, appointment, serviceItem;
  final String admin = "work-admin@example.com";

  @BeforeEach
  void setup() throws Exception {
    clean();
    when(toss.configured()).thenReturn(true);
    when(toss.clientKey()).thenReturn("test_ck_phase5c");
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
    db.update("DELETE FROM treasury_ledger WHERE event_type<>'OPENING_ALLOCATION'");
    db.update(
        "UPDATE treasury_accounts SET balance=CASE account_type"
            + " WHEN 'OPERATING' THEN 400000000 WHEN 'DEPOSIT' THEN 300000000"
            + " ELSE 300000000 END,updated_at=CURRENT_TIMESTAMP");
    db.update("UPDATE inventory_cost_lots SET cost_resolution_id=NULL");
    db.update("DELETE FROM inventory_cost_resolutions");
    db.update("DELETE FROM payment_provider_orders");
    db.update("DELETE FROM payment_records WHERE kind='REVERSAL'");
    db.update("DELETE FROM payment_records");
    db.update("DELETE FROM invoice_items");
    db.update("DELETE FROM invoices");
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

  Object payment(String method, int amount) {
    return Map.of("method", method, "reference", "test", "expectedTotal", amount);
  }

  record BilledWork(UUID work, UUID invoice) {}

  record TossCycle(UUID invoice, String orderId, String paymentKey, BigDecimal amount, UUID payment) {}

  BilledWork billedWork() throws Exception {
    UUID work = work(appointment());
    ok("PATCH", "/api/admin/work-orders/" + work + "/status", Map.of("status", "IN_PROGRESS"));
    complete(work);
    return new BilledWork(work, UUID.fromString(invoice(work)));
  }

  JsonNode prepareToss(UUID invoice, UUID key) throws Exception {
    var prepared = request(
        "POST", "/api/billing/invoices/" + invoice + "/toss/orders", Map.of(), key,
        "work-customer@example.com", true);
    assertThat(prepared.getResponse().getStatus())
        .withFailMessage(prepared.getResponse().getContentAsString())
        .isEqualTo(200);
    return json.readTree(prepared.getResponse().getContentAsString());
  }

  TossCycle confirmToss(UUID invoice, JsonNode order, String suffix) throws Exception {
    String orderId = order.get("orderId").asText();
    BigDecimal amount = order.get("amount").decimalValue();
    String paymentKey = "test_lifecycle_" + suffix;
    var approved = new TossPaymentGateway.Payment(paymentKey, orderId, amount, "DONE");
    when(toss.confirm(paymentKey, orderId, amount)).thenReturn(approved);
    when(toss.lookup(paymentKey)).thenReturn(approved);
    var confirmed = request(
        "POST",
        "/api/billing/toss/confirm",
        Map.of("paymentKey", paymentKey, "orderId", orderId, "amount", amount, "invoiceId", invoice),
        UUID.randomUUID(),
        "work-customer@example.com",
        true);
    assertThat(confirmed.getResponse().getStatus())
        .withFailMessage(confirmed.getResponse().getContentAsString())
        .isEqualTo(200);
    UUID payment = db.queryForObject(
        "SELECT payment_record_id FROM payment_provider_orders WHERE provider_order_id=?",
        UUID.class,
        orderId);
    return new TossCycle(invoice, orderId, paymentKey, amount, payment);
  }

  UUID refundToss(TossCycle cycle, String reason) throws Exception {
    when(toss.cancel(eq(cycle.paymentKey()), anyString(), any()))
        .thenReturn(
            new TossPaymentGateway.Payment(
                cycle.paymentKey(), cycle.orderId(), cycle.amount(), "CANCELED"));
    UUID key = UUID.randomUUID();
    assertThat(
            status(
                "/api/admin/billing/payments/" + cycle.payment() + "/toss-refund",
                Map.of("reason", reason),
                key))
        .isEqualTo(200);
    return key;
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
    assertThat(read("/api/admin/finance/treasury", admin).get("receivables").decimalValue())
        .isEqualByComparingTo("20000");
    assertThat(
            read("/api/admin/finance/treasury", admin)
                .get("managed_assets_known_total")
                .decimalValue())
        .isEqualByComparingTo("1000020000");
    UUID key = UUID.randomUUID();
    assertThat(status(payPath(i), payment(19999), key)).isEqualTo(409);
    assertThat(status(payPath(i), payment(20000), key)).isEqualTo(200);
    assertThat(status(payPath(i), payment(20000), key)).isEqualTo(200);
    assertThat(treasuryBalance("OPERATING")).isEqualByComparingTo("400020000");
    assertThat(count("treasury_ledger WHERE event_type='CUSTOMER_PAYMENT'")).isOne();
    assertThat(read("/api/admin/finance/treasury", admin).get("receivables").decimalValue())
        .isEqualByComparingTo("0");
    assertThat(
            read("/api/admin/finance/treasury", admin)
                .get("managed_assets_known_total")
                .decimalValue())
        .isEqualByComparingTo("1000020000");
    assertThat(status(payPath(i), payment(20000), UUID.randomUUID())).isEqualTo(409);
    String cancel = "/api/admin/billing/invoices/" + i + "/void";
    assertThat(status(cancel, Map.of("reason", "정정"), UUID.randomUUID())).isEqualTo(409);
    String p = db.queryForObject("SELECT id FROM payment_records", UUID.class).toString();
    String reverse = "/api/admin/billing/payments/" + p + "/reverse";
    key = UUID.randomUUID();
    assertThat(status(reverse, Map.of("reason", "수납 취소"), key)).isEqualTo(200);
    assertThat(status(reverse, Map.of("reason", "수납 취소"), key)).isEqualTo(200);
    assertThat(treasuryBalance("OPERATING")).isEqualByComparingTo("400000000");
    assertThat(count("treasury_ledger WHERE event_type='PAYMENT_REFUND'")).isOne();
    assertThat(read("/api/admin/finance/treasury", admin).get("receivables").decimalValue())
        .isEqualByComparingTo("20000");
    assertThat(status(reverse, Map.of("reason", "수납 취소"), UUID.randomUUID())).isEqualTo(409);
    assertThat(status(cancel, Map.of("reason", "정정"), UUID.randomUUID())).isEqualTo(200);
    assertThat(read("/api/admin/finance/treasury", admin).get("receivables").decimalValue())
        .isEqualByComparingTo("0");
    assertThat(count("payment_records")).isEqualTo(2);
    assertThat(count("invoice_items")).isEqualTo(1);
    assertThat(invoice(w)).isNotEqualTo(i);
  }

  @Test
  void historicalPaymentRowsAreNotBackfilledIntoTreasury() throws Exception {
    UUID w = running();
    complete(w);
    UUID invoice = UUID.fromString(invoice(w));
    UUID actor =
        db.queryForObject("SELECT id FROM users WHERE email=?", UUID.class, admin);
    UUID operation = UUID.randomUUID();
    db.update(
        "INSERT INTO stock_operations (id,actor_id,request_hash,response_body,created_at)"
            + " VALUES (?,?,'historical-payment',NULL,CURRENT_TIMESTAMP)",
        operation,
        actor);
    db.update(
        "INSERT INTO payment_records"
            + " (id,invoice_id,operation_id,kind,original_payment_id,amount,method,reference,"
            + "reason,actor_id,created_at) VALUES (?,?,?,'PAYMENT',NULL,20000,'CASH','',"
            + "'V22 이전 수납',?,CURRENT_TIMESTAMP)",
        UUID.randomUUID(),
        invoice,
        operation,
        actor);

    assertThat(read("/api/admin/finance/treasury", admin).get("receivables").decimalValue())
        .isEqualByComparingTo("0");
    assertThat(treasuryBalance("OPERATING")).isEqualByComparingTo("400000000");
    assertThat(count("treasury_ledger WHERE event_type='CUSTOMER_PAYMENT'")).isZero();
  }

  @Test
  void paymentAndTreasuryLedgerRollBackTogetherOnCashLedgerFailure() throws Exception {
    UUID w = running();
    complete(w);
    String invoice = invoice(w);
    UUID key = UUID.randomUUID();
    db.execute(
        "ALTER TABLE treasury_ledger ADD CONSTRAINT test_no_customer_payment"
            + " CHECK (event_type<>'CUSTOMER_PAYMENT')");
    try {
      assertThat(status(payPath(invoice), payment(20000), key)).isEqualTo(409);
      assertThat(count("payment_records")).isZero();
      assertThat(treasuryBalance("OPERATING")).isEqualByComparingTo("400000000");
      assertThat(
              db.queryForObject(
                  "SELECT COUNT(*) FROM stock_operations WHERE id=?", Integer.class, key))
          .isZero();
    } finally {
      db.execute("ALTER TABLE treasury_ledger DROP CONSTRAINT test_no_customer_payment");
    }
    assertThat(status(payPath(invoice), payment(20000), key)).isEqualTo(200);
    assertThat(treasuryBalance("OPERATING")).isEqualByComparingTo("400020000");
  }

  @Test
  void knownReceiptMovesCashToInventoryAndUnknownAndZeroRemainDistinct() throws Exception {
    UUID known = part("KNOWN-ASSET", "0");
    UUID key = UUID.randomUUID();
    Object knownReceipt =
        Map.of("quantity", "10", "reason", "확정 매입", "purchaseUnitCost", "20000");
    assertThat(
            request(
                    "POST",
                    "/api/admin/parts/" + known + "/receipts",
                    knownReceipt,
                    key,
                    admin,
                    true)
                .getResponse()
                .getStatus())
        .isEqualTo(200);
    assertThat(
            request(
                    "POST",
                    "/api/admin/parts/" + known + "/receipts",
                    knownReceipt,
                    key,
                    admin,
                    true)
                .getResponse()
                .getStatus())
        .isEqualTo(200);
    assertThat(balance(known)).isEqualByComparingTo("10");
    assertThat(treasuryBalance("OPERATING")).isEqualByComparingTo("399800000");
    assertThat(count("treasury_ledger WHERE event_type='INVENTORY_PURCHASE'")).isOne();

    UUID unknown = part("UNKNOWN-ASSET", "3");
    UUID zero = part("ZERO-ASSET", "0");
    ok(
        "POST",
        "/api/admin/parts/" + zero + "/receipts",
        Map.of("quantity", "2", "reason", "무상 입고", "purchaseUnitCost", "0"));
    JsonNode treasury = read("/api/admin/finance/treasury", admin);
    assertThat(treasury.get("managed_assets_known_total").decimalValue())
        .isEqualByComparingTo("1000000000");
    assertThat(treasury.get("managed_assets_fully_known").asBoolean()).isFalse();
    assertThat(treasury.get("inventory").get("known_value").decimalValue())
        .isEqualByComparingTo("200000");
    assertThat(treasury.get("inventory").get("unknown_quantity").decimalValue())
        .isEqualByComparingTo("3");
    assertThat(treasury.get("inventory").get("unknown_part_count").asInt()).isOne();
    assertThat(
            db.queryForObject(
                "SELECT cost_known FROM inventory_cost_lots WHERE part_id=?", Boolean.class, zero))
        .isTrue();
    assertThat(treasuryBalance("OPERATING")).isEqualByComparingTo("399800000");
    assertThat(unknown).isNotNull();
  }

  @Test
  void unknownInventoryCostResolutionIsPartialIdempotentAndCashNeutral() throws Exception {
    UUID p = part("RESOLVE-ASSET", "10");
    BigDecimal operating = treasuryBalance("OPERATING");
    UUID key = UUID.randomUUID();
    Object request = Map.of("quantity", "4", "unitCost", "70000", "reason", "초기 재고 원가 등록");
    String path = "/api/admin/parts/" + p + "/cost-resolutions";

    assertThat(status(path, request, key)).isEqualTo(200);
    assertThat(status(path, request, key)).isEqualTo(200);
    JsonNode inventory = read("/api/admin/finance/treasury", admin).get("inventory");
    JsonNode row = inventory.get("items").findValuesAsText("part_id").isEmpty() ? null :
        java.util.stream.StreamSupport.stream(inventory.get("items").spliterator(), false)
            .filter(item -> p.toString().equals(item.get("part_id").asText())).findFirst().orElseThrow();
    assertThat(row.get("known_quantity").decimalValue()).isEqualByComparingTo("4");
    assertThat(row.get("unknown_quantity").decimalValue()).isEqualByComparingTo("6");
    assertThat(row.get("known_asset_value").decimalValue()).isEqualByComparingTo("280000");
    assertThat(treasuryBalance("OPERATING")).isEqualByComparingTo(operating);
    assertThat(count("inventory_cost_resolutions WHERE part_id='" + p + "'")).isOne();

    assertThat(status(path, Map.of("quantity", "6", "unitCost", "0", "reason", "무상 기초재고"), UUID.randomUUID()))
        .isEqualTo(200);
    inventory = read("/api/admin/finance/treasury", admin).get("inventory");
    assertThat(inventory.get("unknown_quantity").decimalValue()).isEqualByComparingTo("0");
    assertThat(treasuryBalance("OPERATING")).isEqualByComparingTo(operating);
  }

  @Test
  void customerTossOrderRequiresInvoiceOwnershipAndRejectsPaidInvoice() throws Exception {
    UUID w = running();
    complete(w);
    UUID invoice = UUID.fromString(invoice(w));
    String customerEmail = "work-customer@example.com";
    users.saveAndFlush(
        new AppUser("other-customer@example.com", "test-hash", "다른 고객", AppUser.Role.CUSTOMER));

    var owned = request(
        "POST", "/api/billing/invoices/" + invoice + "/toss/orders", Map.of(),
        UUID.randomUUID(), customerEmail, true);
    assertThat(owned.getResponse().getStatus()).isEqualTo(200);
    var foreign = request(
        "POST", "/api/billing/invoices/" + invoice + "/toss/orders", Map.of(),
        UUID.randomUUID(), "other-customer@example.com", true);
    assertThat(foreign.getResponse().getStatus()).isEqualTo(404);

    UUID paidWork = work(appointment());
    ok("PATCH", "/api/admin/work-orders/" + paidWork + "/status", Map.of("status", "IN_PROGRESS"));
    complete(paidWork);
    String paidInvoice = invoice(paidWork);
    assertThat(status(payPath(paidInvoice), payment(20000), UUID.randomUUID())).isEqualTo(200);
    var duplicate = request(
        "POST", "/api/billing/invoices/" + paidInvoice + "/toss/orders", Map.of(),
        UUID.randomUUID(), customerEmail, true);
    assertThat(duplicate.getResponse().getStatus()).isEqualTo(409);
  }

  @Test
  void adminTossOrderAndConfirmKeepInvoiceCustomerIdentity() throws Exception {
    UUID w = running();
    complete(w);
    UUID invoice = UUID.fromString(invoice(w));
    var prepared = request(
        "POST", "/api/admin/billing/invoices/" + invoice + "/toss/orders", Map.of(),
        UUID.randomUUID(), admin, true);
    assertThat(prepared.getResponse().getStatus()).isEqualTo(200);
    JsonNode order = json.readTree(prepared.getResponse().getContentAsString());
    String expectedCustomerKey = "customer_" + customer.toString().replace("-", "");
    assertThat(order.get("customerKey").asText()).isEqualTo(expectedCustomerKey);
    assertThat(order.get("customerEmail").asText()).isEqualTo("work-customer@example.com");
    assertThat(
            db.queryForObject(
                "SELECT customer_id FROM payment_provider_orders WHERE invoice_id=?",
                UUID.class,
                invoice))
        .isEqualTo(customer);

    String orderId = order.get("orderId").asText();
    BigDecimal amount = order.get("amount").decimalValue();
    String paymentKey = "test_admin_counter_payment";
    when(toss.confirm(paymentKey, orderId, amount))
        .thenReturn(new TossPaymentGateway.Payment(paymentKey, orderId, amount, "DONE"));
    var confirmed = request(
        "POST",
        "/api/admin/billing/toss/confirm",
        Map.of("paymentKey", paymentKey, "orderId", orderId, "amount", amount, "invoiceId", invoice),
        UUID.randomUUID(),
        admin,
        true);
    assertThat(confirmed.getResponse().getStatus()).isEqualTo(200);
    assertThat(
            db.queryForObject(
                "SELECT actor_id FROM payment_records WHERE invoice_id=? AND kind='PAYMENT'",
                UUID.class,
                invoice))
        .isEqualTo(customer);
    assertThat(count("treasury_ledger WHERE event_type='CUSTOMER_PAYMENT'")).isOne();

    var duplicate = request(
        "POST", "/api/admin/billing/invoices/" + invoice + "/toss/orders", Map.of(),
        UUID.randomUUID(), admin, true);
    assertThat(duplicate.getResponse().getStatus()).isEqualTo(409);
  }

  @Test
  void onlyAdminCanReleaseAndOpenReceivableMustBePaidFirst() throws Exception {
    UUID w = running();
    complete(w);
    String invoice = invoice(w);

    var unpaidRelease = request(
        "POST", "/api/admin/work-orders/" + w + "/release", Map.of(),
        UUID.randomUUID(), admin, true);
    assertThat(unpaidRelease.getResponse().getStatus()).isEqualTo(409);

    var mechanicRelease =
        mvc.perform(
                post("/api/admin/work-orders/" + w + "/release")
                    .with(user("mechanic-release@example.com").roles("MECHANIC"))
                    .with(csrf())
                    .header("Idempotency-Key", UUID.randomUUID())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{}"))
            .andReturn();
    assertThat(mechanicRelease.getResponse().getStatus()).isEqualTo(403);
    var mechanicBilling =
        mvc.perform(
                post("/api/admin/billing/invoices/" + invoice + "/payments")
                    .with(user("mechanic-billing@example.com").roles("MECHANIC"))
                    .with(csrf())
                    .header("Idempotency-Key", UUID.randomUUID())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(payment(20000))))
            .andReturn();
    assertThat(mechanicBilling.getResponse().getStatus()).isEqualTo(403);

    assertThat(status(payPath(invoice), payment(20000), UUID.randomUUID())).isEqualTo(200);
    var released = request(
        "POST", "/api/admin/work-orders/" + w + "/release", Map.of(),
        UUID.randomUUID(), admin, true);
    assertThat(released.getResponse().getStatus()).isEqualTo(200);
    assertThat(json.readTree(released.getResponse().getContentAsString()).get("released_at").isNull())
        .isFalse();
  }

  @Test
  void tossConfirmRefundAndRetryAreIdempotentAndCashIntegrated() throws Exception {
    UUID w = running();
    complete(w);
    UUID invoice = UUID.fromString(invoice(w));
    String customerEmail = "work-customer@example.com";
    UUID prepareKey = UUID.randomUUID();
    var prepared = request("POST", "/api/billing/invoices/" + invoice + "/toss/orders", Map.of(), prepareKey, customerEmail, true);
    assertThat(prepared.getResponse().getStatus()).isEqualTo(200);
    JsonNode order = json.readTree(prepared.getResponse().getContentAsString());
    String orderId = order.get("orderId").asText();
    String paymentKey = "test_payment_key_phase5c";
    BigDecimal amount = order.get("amount").decimalValue();
    var approved = new TossPaymentGateway.Payment(paymentKey, orderId, amount, "DONE");
    when(toss.confirm(paymentKey, orderId, amount)).thenReturn(approved);
    when(toss.lookup(paymentKey)).thenReturn(approved);
    UUID confirmKey = UUID.randomUUID();
    Object confirm = Map.of("paymentKey", paymentKey, "orderId", orderId, "amount", amount, "invoiceId", invoice);

    var invalid = request("POST", "/api/billing/toss/confirm",
        Map.of("paymentKey", paymentKey, "orderId", orderId, "amount", amount.add(BigDecimal.ONE), "invoiceId", invoice),
        UUID.randomUUID(), customerEmail, true);
    assertThat(invalid.getResponse().getStatus()).isEqualTo(409);
    db.execute("ALTER TABLE treasury_ledger ADD CONSTRAINT test_toss_internal_failure CHECK (event_type<>'CUSTOMER_PAYMENT')");
    try {
      var failed = request("POST", "/api/billing/toss/confirm", confirm, confirmKey, customerEmail, true);
      assertThat(failed.getResponse().getStatus()).isEqualTo(409);
      assertThat(count("payment_records")).isZero();
    } finally {
      db.execute("ALTER TABLE treasury_ledger DROP CONSTRAINT test_toss_internal_failure");
    }
    var recovered = request("POST", "/api/billing/toss/confirm", confirm, confirmKey, customerEmail, true);
    var duplicate = request("POST", "/api/billing/toss/confirm", confirm, confirmKey, customerEmail, true);
    var refreshed = request("POST", "/api/billing/toss/confirm", confirm, UUID.randomUUID(), customerEmail, true);
    assertThat(recovered.getResponse().getStatus()).isEqualTo(200);
    assertThat(duplicate.getResponse().getStatus()).isEqualTo(200);
    assertThat(refreshed.getResponse().getStatus()).isEqualTo(200);
    assertThat(count("payment_records WHERE kind='PAYMENT'")).isOne();
    assertThat(count("treasury_ledger WHERE event_type='CUSTOMER_PAYMENT'")).isOne();
    assertThat(read("/api/admin/finance/treasury", admin).get("receivables").decimalValue()).isEqualByComparingTo("0");
    verify(toss, times(1)).confirm(paymentKey, orderId, amount);
    verify(toss, atLeastOnce()).lookup(paymentKey);

    UUID payment = db.queryForObject("SELECT id FROM payment_records WHERE kind='PAYMENT'", UUID.class);
    var cancelled = new TossPaymentGateway.Payment(paymentKey, orderId, amount, "CANCELED");
    when(toss.cancel(eq(paymentKey), anyString(), any())).thenReturn(cancelled);
    UUID refundKey = UUID.randomUUID();
    String refundPath = "/api/admin/billing/payments/" + payment + "/toss-refund";
    assertThat(status(refundPath, Map.of("reason", "고객 요청"), refundKey)).isEqualTo(200);
    assertThat(status(refundPath, Map.of("reason", "고객 요청"), refundKey)).isEqualTo(200);
    assertThat(status(refundPath, Map.of("reason", "고객 요청"), UUID.randomUUID())).isEqualTo(409);
    assertThat(count("payment_records WHERE kind='REVERSAL'")).isOne();
    assertThat(count("treasury_ledger WHERE event_type='PAYMENT_REFUND'")).isOne();
    assertThat(read("/api/admin/finance/treasury", admin).get("receivables").decimalValue()).isEqualByComparingTo(amount);
  }

  @Test
  void tossRefundAllowsNewProviderAttemptWithoutReusingCanceledOrder() throws Exception {
    BilledWork billed = billedWork();
    UUID firstPrepareKey = UUID.randomUUID();
    JsonNode firstOrder = prepareToss(billed.invoice(), firstPrepareKey);
    var adminPrepared = request(
        "POST",
        "/api/admin/billing/invoices/" + billed.invoice() + "/toss/orders",
        Map.of(),
        UUID.randomUUID(),
        admin,
        true);
    assertThat(adminPrepared.getResponse().getStatus()).isEqualTo(200);
    assertThat(json.readTree(adminPrepared.getResponse().getContentAsString()).get("orderId").asText())
        .isEqualTo(firstOrder.get("orderId").asText());
    TossCycle first = confirmToss(billed.invoice(), firstOrder, "attempt_a");
    UUID refundKey = refundToss(first, "재결제 테스트");

    assertThat(
            status(
                "/api/admin/billing/payments/" + first.payment() + "/toss-refund",
                Map.of("reason", "재결제 테스트"),
                refundKey))
        .isEqualTo(200);
    assertThat(prepareToss(billed.invoice(), firstPrepareKey).get("orderId").asText())
        .isEqualTo(first.orderId());

    JsonNode secondOrder = prepareToss(billed.invoice(), UUID.randomUUID());
    assertThat(secondOrder.get("orderId").asText()).isNotEqualTo(first.orderId());
    assertThat(
            db.queryForObject(
                "SELECT status FROM payment_provider_orders WHERE provider_order_id=?",
                String.class,
                first.orderId()))
        .isEqualTo("CANCELED");
    TossCycle second = confirmToss(billed.invoice(), secondOrder, "attempt_b");
    assertThat(second.payment()).isNotEqualTo(first.payment());
    assertThat(count("payment_provider_orders WHERE invoice_id='" + billed.invoice() + "'"))
        .isEqualTo(2);
    JsonNode detail = read("/api/admin/billing/invoices/" + billed.invoice(), admin);
    assertThat(detail.get("paid").decimalValue()).isEqualByComparingTo("20000");
    assertThat(detail.get("balance").decimalValue()).isEqualByComparingTo("0");
  }

  @Test
  void tossRefundAllowsCashOrTransferWithoutCreatingProviderOrder() throws Exception {
    for (String method : List.of("CASH", "TRANSFER")) {
      BilledWork billed = billedWork();
      TossCycle tossPayment =
          confirmToss(
              billed.invoice(),
              prepareToss(billed.invoice(), UUID.randomUUID()),
              "refund_to_" + method.toLowerCase(Locale.ROOT));
      refundToss(tossPayment, method + " 재수납");
      assertThat(
              request(
                      "POST",
                      "/api/admin/work-orders/" + billed.work() + "/release",
                      Map.of(),
                      UUID.randomUUID(),
                      admin,
                      true)
                  .getResponse()
                  .getStatus())
          .isEqualTo(409);
      int providerOrders = count("payment_provider_orders WHERE invoice_id='" + billed.invoice() + "'");
      assertThat(
              status(
                  payPath(billed.invoice().toString()),
                  payment(method, 20000),
                  UUID.randomUUID()))
          .isEqualTo(200);
      assertThat(count("payment_provider_orders WHERE invoice_id='" + billed.invoice() + "'"))
          .isEqualTo(providerOrders);
      assertThat(
              db.queryForObject(
                  "SELECT method FROM payment_records WHERE invoice_id=? AND kind='PAYMENT'"
                      + " ORDER BY created_at DESC,id DESC LIMIT 1",
                  String.class,
                  billed.invoice()))
          .isEqualTo(method);
      assertThat(
              read("/api/admin/billing/invoices/" + billed.invoice(), admin)
                  .get("balance")
                  .decimalValue())
          .isEqualByComparingTo("0");
      assertThat(
              request(
                      "POST",
                      "/api/admin/work-orders/" + billed.work() + "/release",
                      Map.of(),
                      UUID.randomUUID(),
                      admin,
                      true)
                  .getResponse()
                  .getStatus())
          .isEqualTo(200);
    }
  }

  @Test
  void manualReversalAllowsTossOrAnotherManualMethodAndVoidRejectsPayments() throws Exception {
    BilledWork cashThenToss = billedWork();
    assertThat(
            status(
                payPath(cashThenToss.invoice().toString()),
                payment("CASH", 20000),
                UUID.randomUUID()))
        .isEqualTo(200);
    UUID cashPayment = db.queryForObject(
        "SELECT id FROM payment_records WHERE invoice_id=? AND kind='PAYMENT'",
        UUID.class,
        cashThenToss.invoice());
    assertThat(
            status(
                "/api/admin/billing/payments/" + cashPayment + "/reverse",
                Map.of("reason", "결제수단 변경"),
                UUID.randomUUID()))
        .isEqualTo(200);
    confirmToss(
        cashThenToss.invoice(),
        prepareToss(cashThenToss.invoice(), UUID.randomUUID()),
        "cash_to_toss");

    BilledWork cardThenCash = billedWork();
    assertThat(
            status(
                payPath(cardThenCash.invoice().toString()),
                payment("CARD", 20000),
                UUID.randomUUID()))
        .isEqualTo(200);
    UUID cardPayment = db.queryForObject(
        "SELECT id FROM payment_records WHERE invoice_id=? AND kind='PAYMENT'",
        UUID.class,
        cardThenCash.invoice());
    assertThat(
            status(
                "/api/admin/billing/payments/" + cardPayment + "/reverse",
                Map.of("reason", "현금 전환"),
                UUID.randomUUID()))
        .isEqualTo(200);
    assertThat(
            status(
                payPath(cardThenCash.invoice().toString()),
                payment("CASH", 20000),
                UUID.randomUUID()))
        .isEqualTo(200);
    assertThat(
            status(
                payPath(cardThenCash.invoice().toString()),
                payment("TRANSFER", 20000),
                UUID.randomUUID()))
        .isEqualTo(409);

    BilledWork voided = billedWork();
    assertThat(
            status(
                "/api/admin/billing/invoices/" + voided.invoice() + "/void",
                Map.of("reason", "명세 무효"),
                UUID.randomUUID()))
        .isEqualTo(200);
    assertThat(
            status(
                payPath(voided.invoice().toString()),
                payment("CASH", 20000),
                UUID.randomUUID()))
        .isEqualTo(409);
    var tossOnVoid = request(
        "POST",
        "/api/billing/invoices/" + voided.invoice() + "/toss/orders",
        Map.of(),
        UUID.randomUUID(),
        "work-customer@example.com",
        true);
    assertThat(tossOnVoid.getResponse().getStatus()).isEqualTo(409);
  }

  @Test
  void concurrentTossConfirmCreatesOnePaymentAndOneCashMutation() throws Exception {
    UUID w = running();
    complete(w);
    UUID invoice = UUID.fromString(invoice(w));
    String customerEmail = "work-customer@example.com";
    var prepared = request("POST", "/api/billing/invoices/" + invoice + "/toss/orders", Map.of(),
        UUID.randomUUID(), customerEmail, true);
    JsonNode order = json.readTree(prepared.getResponse().getContentAsString());
    String orderId = order.get("orderId").asText();
    BigDecimal amount = order.get("amount").decimalValue();
    String paymentKey = "test_concurrent_payment_key";
    var approved = new TossPaymentGateway.Payment(paymentKey, orderId, amount, "DONE");
    CountDownLatch confirming = new CountDownLatch(1), release = new CountDownLatch(1);
    when(toss.confirm(paymentKey, orderId, amount)).thenAnswer(call -> {
      confirming.countDown();
      assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
      return approved;
    });
    when(toss.lookup(paymentKey)).thenReturn(approved);
    Object body = Map.of("paymentKey", paymentKey, "orderId", orderId, "amount", amount, "invoiceId", invoice);
    var pool = Executors.newFixedThreadPool(2);
    try {
      var first = pool.submit(() -> request("POST", "/api/billing/toss/confirm", body, UUID.randomUUID(), customerEmail, true));
      assertThat(confirming.await(5, TimeUnit.SECONDS)).isTrue();
      assertThat(status(payPath(invoice.toString()), payment(20000), UUID.randomUUID()))
          .isEqualTo(409);
      var second = pool.submit(() -> request("POST", "/api/billing/toss/confirm", body, UUID.randomUUID(), customerEmail, true));
      assertThat(second.get(5, TimeUnit.SECONDS).getResponse().getStatus()).isEqualTo(200);
      release.countDown();
      assertThat(first.get(5, TimeUnit.SECONDS).getResponse().getStatus()).isEqualTo(200);
    } finally {
      release.countDown();
      pool.shutdownNow();
    }
    assertThat(count("payment_records WHERE kind='PAYMENT'")).isOne();
    assertThat(count("treasury_ledger WHERE event_type='CUSTOMER_PAYMENT'")).isOne();
  }

  @Test
  void insufficientCashAndLedgerFailureRollBackWholeKnownReceipt() throws Exception {
    UUID p = part("ROLLBACK-ASSET", "0");
    db.update("UPDATE treasury_accounts SET balance=100 WHERE account_type='OPERATING'");
    Object body =
        Map.of("quantity", "1", "reason", "고가 매입", "purchaseUnitCost", "200");
    assertThat(status("/api/admin/parts/" + p + "/receipts", body, UUID.randomUUID()))
        .isEqualTo(409);
    assertThat(balance(p)).isEqualByComparingTo("0");
    assertThat(count("stock_movements WHERE part_id='" + p + "'")).isZero();
    assertThat(count("inventory_cost_lots WHERE part_id='" + p + "'")).isZero();

    db.update("UPDATE treasury_accounts SET balance=400000000 WHERE account_type='OPERATING'");
    db.execute(
        "ALTER TABLE treasury_ledger ADD CONSTRAINT test_no_inventory_purchase"
            + " CHECK (event_type<>'INVENTORY_PURCHASE')");
    try {
      assertThat(status("/api/admin/parts/" + p + "/receipts", body, UUID.randomUUID()))
          .isEqualTo(409);
      assertThat(balance(p)).isEqualByComparingTo("0");
      assertThat(treasuryBalance("OPERATING")).isEqualByComparingTo("400000000");
      assertThat(count("inventory_cost_lots WHERE part_id='" + p + "'")).isZero();
    } finally {
      db.execute("ALTER TABLE treasury_ledger DROP CONSTRAINT test_no_inventory_purchase");
    }
  }

  @Test
  void useReturnAndAdjustmentsRevalueInventoryWithoutMovingTreasury() throws Exception {
    UUID p = part("VALUATION-ASSET", "0");
    ok(
        "POST",
        "/api/admin/parts/" + p + "/receipts",
        Map.of("quantity", "2", "reason", "확정 매입", "purchaseUnitCost", "10000"));
    UUID w = running();
    String use = ok("POST", usePath(w), use(p, "1")).get("movements").get(0).get("id").asText();
    assertInventory("10000", "0");
    ok(
        "POST",
        "/api/admin/work-orders/" + w + "/parts/return",
        Map.of("originalUseId", use, "quantity", "0.5", "reason", "실물 반환"));
    assertInventory("15000", "0");
    ok(
        "POST",
        "/api/admin/parts/" + p + "/adjustments",
        Map.of("quantity", "1", "expectedQuantity", "1.5", "reason", "감모"));
    assertInventory("10000", "0");
    ok(
        "POST",
        "/api/admin/parts/" + p + "/adjustments",
        Map.of("quantity", "2", "expectedQuantity", "1", "reason", "미확정 발견"));
    assertInventory("10000", "1");
    assertThat(treasuryBalance("OPERATING")).isEqualByComparingTo("399980000");
  }

  private void assertInventory(String known, String unknown) throws Exception {
    JsonNode inventory = read("/api/admin/finance/treasury", admin).get("inventory");
    assertThat(inventory.get("known_value").decimalValue()).isEqualByComparingTo(known);
    assertThat(inventory.get("unknown_quantity").decimalValue()).isEqualByComparingTo(unknown);
  }

  private BigDecimal treasuryBalance(String type) {
    return db.queryForObject(
        "SELECT balance FROM treasury_accounts WHERE account_type=?", BigDecimal.class, type);
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

  @Test
  void skippedLaborIsExcludedFromBillingLines() throws Exception {
    UUID w = running();
    UUID item = db.queryForObject("SELECT id FROM work_order_items WHERE work_order_id=?", UUID.class, w);
    ok("PATCH", "/api/admin/work-orders/" + w + "/items/" + item,
        Map.of("status", "SKIPPED", "reason", "고객 미승인"));
    ok("PATCH", "/api/admin/work-orders/" + w + "/status", Map.of("status", "COMPLETED"));
    var preview = read("/api/admin/billing/work-orders/" + w + "/preview", admin);
    assertThat(preview.get("items")).isEmpty();
  }
}
