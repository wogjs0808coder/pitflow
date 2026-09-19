package com.pitflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pitflow.user.AppUser;
import com.pitflow.user.UserRepository;
import com.pitflow.vehicle.Vehicle;
import com.pitflow.vehicle.VehicleRepository;
import com.pitflow.vehicle.VehicleRequest;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class FinanceCostInventoryIntegrationTest {
  @Autowired MockMvc mvc;
  @Autowired ObjectMapper json;
  @Autowired JdbcTemplate db;
  @Autowired UserRepository users;
  @Autowired VehicleRepository vehicles;

  final String admin = "cost-admin@example.com";
  UUID customer;
  UUID vehicle;
  UUID mechanic;
  UUID service;
  UUID appointment;

  @BeforeEach
  void setup() throws Exception {
    clean();
    customer =
        users
            .saveAndFlush(new AppUser("cost-customer@example.com", "hash", "고객", AppUser.Role.CUSTOMER))
            .getId();
    users.saveAndFlush(new AppUser(admin, "hash", "관리자", AppUser.Role.ADMIN));
    vehicle =
        vehicles
            .saveAndFlush(
                new Vehicle(customer, new VehicleRequest("44가4404", "현대", "아반떼", 2025, 1000)))
            .getId();
    mechanic =
        UUID.fromString(
            ok("POST", "/api/admin/mechanics", Map.of("code", "COST", "name", "원가", "active", true))
                .get("id")
                .asText());
    service = UUID.randomUUID();
    db.update(
        "INSERT INTO service_items (id,name,description,labor_price,duration_minutes,active,created_at,updated_at) VALUES (?,?,?,20000,30,TRUE,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
        service,
        "원가 테스트 정비",
        "원가 테스트");
    appointment = appointment("2026-09-21T01:00:00Z");
  }

  @AfterEach
  void clean() {
    db.update("DELETE FROM notifications");
    db.update("DELETE FROM part_shortage_reports");
    db.update("DELETE FROM payment_records WHERE kind='REVERSAL'");
    db.update("DELETE FROM payment_records");
    db.update("DELETE FROM invoice_items");
    db.update("DELETE FROM invoices");
    db.update("DELETE FROM inventory_cost_allocations");
    db.update("DELETE FROM inventory_cost_lots");
    db.update("DELETE FROM stock_movements WHERE kind='RETURN'");
    db.update("DELETE FROM stock_movements");
    db.update("DELETE FROM work_order_events");
    db.update("DELETE FROM work_order_items");
    db.update("DELETE FROM work_orders");
    db.update("DELETE FROM stock_operations");
    db.update("DELETE FROM mechanics");
    db.update("DELETE FROM parts WHERE sku LIKE 'COST-%'");
    db.update("DELETE FROM slot_allocations");
    db.update("DELETE FROM appointment_items");
    db.update("DELETE FROM appointments");
    vehicles.deleteAll();
    users.deleteAll();
    db.update("DELETE FROM service_items WHERE name='원가 테스트 정비'");
  }

  UUID appointment(String startText) {
    UUID id = UUID.randomUUID();
    OffsetDateTime start = OffsetDateTime.parse(startText);
    db.update(
        "INSERT INTO appointments (id,customer_id,vehicle_id,work_bay_id,plate_number,vehicle_label,starts_at,ends_at,status,notes,total_labor_price,duration_minutes,created_at,updated_at) VALUES (?,?,?,'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaa1','44가4404','현대 아반떼',?,?,'VISITED','',20000,30,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
        id,
        customer,
        vehicle,
        start,
        start.plusMinutes(30));
    db.update(
        "INSERT INTO appointment_items (appointment_id,service_item_id,name,labor_price,duration_minutes) VALUES (?,?,?,20000,30)",
        id,
        service,
        "원가 테스트 정비");
    return id;
  }

  MvcResult request(String method, String path, Object body, UUID key) throws Exception {
    var builder = "PATCH".equals(method) ? patch(path) : post(path);
    builder.with(user(admin).roles("ADMIN")).with(csrf());
    if (key != null) builder.header("Idempotency-Key", key.toString());
    return mvc.perform(
            builder.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)))
        .andReturn();
  }

  JsonNode ok(String method, String path, Object body) throws Exception {
    return ok(method, path, body, UUID.randomUUID());
  }

  JsonNode ok(String method, String path, Object body, UUID key) throws Exception {
    MvcResult result = request(method, path, body, key);
    assertThat(result.getResponse().getStatus())
        .withFailMessage(result.getResponse().getContentAsString())
        .isEqualTo(200);
    return json.readTree(result.getResponse().getContentAsString());
  }

  JsonNode read(String path) throws Exception {
    MvcResult result = mvc.perform(get(path).with(user(admin).roles("ADMIN"))).andReturn();
    assertThat(result.getResponse().getStatus())
        .withFailMessage(result.getResponse().getContentAsString())
        .isEqualTo(200);
    return json.readTree(result.getResponse().getContentAsString());
  }

  UUID work(UUID visit) throws Exception {
    UUID id =
        UUID.fromString(
            ok(
                    "POST",
                    "/api/admin/work-orders/from-appointment",
                    Map.of(
                        "appointmentId", visit,
                        "receivedMileage", 1100,
                        "mechanicId", mechanic,
                        "notes", "확인"))
                .get("id")
                .asText());
    ok("PATCH", "/api/admin/work-orders/" + id + "/status", Map.of("status", "IN_PROGRESS"));
    return id;
  }

  UUID part(String sku) throws Exception {
    return UUID.fromString(
        ok(
                "POST",
                "/api/admin/parts",
                Map.of(
                    "sku", sku,
                    "name", sku,
                    "unit", "EA",
                    "minimumQuantity", "0",
                    "unitPrice", "20000",
                    "active", true))
            .get("id")
            .asText());
  }

  JsonNode receipt(UUID part, String quantity, String cost, UUID key) throws Exception {
    var body = new java.util.LinkedHashMap<String, Object>();
    body.put("quantity", quantity);
    body.put("reason", "입고");
    if (cost != null) body.put("purchaseUnitCost", cost);
    return ok("POST", "/api/admin/parts/" + part + "/receipts", body, key);
  }

  JsonNode use(UUID work, UUID part, String quantity, UUID key) throws Exception {
    return ok(
        "POST",
        "/api/admin/work-orders/" + work + "/parts/use",
        Map.of(
            "lines", List.of(Map.of("partId", part, "quantity", quantity)),
            "reason", "정비 사용"),
        key);
  }

  BigDecimal quantity(UUID part) {
    return db.queryForObject("SELECT quantity FROM parts WHERE id=?", BigDecimal.class, part);
  }

  BigDecimal lotQuantity(UUID part) {
    return db.queryForObject(
        "SELECT COALESCE(SUM(remaining_quantity),0) FROM inventory_cost_lots WHERE part_id=?",
        BigDecimal.class,
        part);
  }

  void assertReconciled(UUID part) {
    assertThat(lotQuantity(part)).isEqualByComparingTo(quantity(part));
  }

  @Test
  void receiptsKeepSalesPriceSeparateAndIdempotencyIncludesPurchaseCost() throws Exception {
    UUID part = part("COST-RECEIPT");
    UUID key = UUID.randomUUID();
    JsonNode first = receipt(part, "2", "12000", key);
    assertThat(receipt(part, "2", "12000", key)).isEqualTo(first);
    assertThat(quantity(part)).isEqualByComparingTo("2");
    assertThat(
            db.queryForObject(
                "SELECT purchase_unit_cost FROM inventory_cost_lots WHERE part_id=?",
                BigDecimal.class,
                part))
        .isEqualByComparingTo("12000");
    assertThat(
            db.queryForObject(
                "SELECT unit_price FROM stock_movements WHERE part_id=?", BigDecimal.class, part))
        .isEqualByComparingTo("20000");
    assertThat(
            request(
                    "POST",
                    "/api/admin/parts/" + part + "/receipts",
                    Map.of("quantity", "2", "reason", "입고", "purchaseUnitCost", "13000"),
                    key)
                .getResponse()
                .getStatus())
        .isEqualTo(409);
    receipt(part, "1", null, UUID.randomUUID());
    assertThat(
            db.queryForObject(
                "SELECT COUNT(*) FROM inventory_cost_lots WHERE part_id=? AND cost_known=FALSE AND purchase_unit_cost IS NULL",
                Integer.class,
                part))
        .isEqualTo(1);
    assertThat(
            db.queryForObject(
                "SELECT COUNT(*) FROM stock_movements WHERE part_id=? AND kind='RECEIPT'",
                Integer.class,
                part))
        .isEqualTo(2);
    assertReconciled(part);
  }

  @Test
  void fifoUseAndRepeatedPartialReturnRestoreExactLots() throws Exception {
    UUID work = work(appointment), part = part("COST-FIFO");
    db.update("UPDATE parts SET unit='L' WHERE id=?", part);
    receipt(part, "1", "100", UUID.randomUUID());
    receipt(part, "2", "200", UUID.randomUUID());
    var lots =
        db.queryForList(
            "SELECT id,purchase_unit_cost FROM inventory_cost_lots WHERE part_id=? ORDER BY purchase_unit_cost",
            part);
    db.update(
        "UPDATE inventory_cost_lots SET received_at=? WHERE id=?",
        OffsetDateTime.parse("2026-09-20T01:00:00Z"),
        lots.get(0).get("id"));
    db.update(
        "UPDATE inventory_cost_lots SET received_at=? WHERE id=?",
        OffsetDateTime.parse("2026-09-20T02:00:00Z"),
        lots.get(1).get("id"));
    UUID useKey = UUID.randomUUID();
    JsonNode useResponse = use(work, part, "2.5", useKey);
    UUID use =
        UUID.fromString(
            useResponse.get("movements").get(0).get("id").asText());
    assertThat(use(work, part, "2.5", useKey)).isEqualTo(useResponse);
    var consumed =
        db.queryForList(
            "SELECT quantity,purchase_unit_cost FROM inventory_cost_allocations WHERE movement_id=? ORDER BY purchase_unit_cost",
            use);
    assertThat(consumed).hasSize(2);
    assertThat((BigDecimal) consumed.get(0).get("quantity")).isEqualByComparingTo("1");
    assertThat((BigDecimal) consumed.get(1).get("quantity")).isEqualByComparingTo("1.5");

    String path = "/api/admin/work-orders/" + work + "/parts/return";
    ok(
        "POST",
        path,
        Map.of("originalUseId", use, "quantity", "0.5", "reason", "부분 반환"));
    ok(
        "POST",
        path,
        Map.of("originalUseId", use, "quantity", "2", "reason", "나머지 반환"));
    assertThat(quantity(part)).isEqualByComparingTo("3");
    assertThat(
            db.queryForObject(
                "SELECT remaining_quantity FROM inventory_cost_lots WHERE part_id=? AND purchase_unit_cost=100",
                BigDecimal.class,
                part))
        .isEqualByComparingTo("1");
    assertThat(
            db.queryForObject(
                "SELECT remaining_quantity FROM inventory_cost_lots WHERE part_id=? AND purchase_unit_cost=200",
                BigDecimal.class,
                part))
        .isEqualByComparingTo("2");
    assertThat(
            db.queryForObject(
                "SELECT COUNT(*) FROM inventory_cost_allocations WHERE allocation_type='RESTORE'",
                Integer.class))
        .isEqualTo(3);
    assertThat(
            request(
                    "POST",
                    path,
                    Map.of("originalUseId", use, "quantity", "0.001", "reason", "초과 반환"),
                    UUID.randomUUID())
                .getResponse()
                .getStatus())
        .isEqualTo(409);
    assertReconciled(part);

    UUID tied = part("COST-TIE");
    receipt(tied, "1", "300", UUID.randomUUID());
    receipt(tied, "1", "400", UUID.randomUUID());
    db.update(
        "UPDATE inventory_cost_lots SET received_at=? WHERE part_id=?",
        OffsetDateTime.parse("2026-09-20T03:00:00Z"),
        tied);
    BigDecimal firstCost =
        db.queryForObject(
            "SELECT purchase_unit_cost FROM inventory_cost_lots WHERE part_id=? ORDER BY received_at,id LIMIT 1",
            BigDecimal.class,
            tied);
    UUID tiedUse =
        UUID.fromString(
            use(work, tied, "1", UUID.randomUUID()).get("movements").get(0).get("id").asText());
    assertThat(
            db.queryForObject(
                "SELECT purchase_unit_cost FROM inventory_cost_allocations WHERE movement_id=?",
                BigDecimal.class,
                tiedUse))
        .isEqualByComparingTo(firstCost);
    assertReconciled(tied);
  }

  @Test
  void adjustmentsAndFailuresAreAtomic() throws Exception {
    UUID work = work(appointment), part = part("COST-ADJUST");
    receipt(part, "2", "900", UUID.randomUUID());
    ok(
        "POST",
        "/api/admin/parts/" + part + "/adjustments",
        Map.of("quantity", "1", "expectedQuantity", "2", "reason", "실사 감소"));
    assertThat(lotQuantity(part)).isEqualByComparingTo("1");
    ok(
        "POST",
        "/api/admin/parts/" + part + "/adjustments",
        Map.of("quantity", "3", "expectedQuantity", "1", "reason", "실사 증가"));
    assertThat(
            db.queryForObject(
                "SELECT COUNT(*) FROM inventory_cost_lots WHERE part_id=? AND origin='ADJUST_IN' AND cost_known=FALSE",
                Integer.class,
                part))
        .isEqualTo(1);
    assertThat(
            request(
                    "POST",
                    "/api/admin/parts/" + part + "/adjustments",
                    Map.of("quantity", "2", "expectedQuantity", "1", "reason", "오래된 화면"),
                    UUID.randomUUID())
                .getResponse()
                .getStatus())
        .isEqualTo(409);
    assertThat(
            request(
                    "POST",
                    "/api/admin/work-orders/" + work + "/parts/use",
                    Map.of(
                        "lines", List.of(Map.of("partId", part, "quantity", "4")),
                        "reason", "재고 초과"),
                    UUID.randomUUID())
                .getResponse()
                .getStatus())
        .isEqualTo(409);

    BigDecimal before = quantity(part);
    int movements = db.queryForObject("SELECT COUNT(*) FROM stock_movements", Integer.class);
    int operations = db.queryForObject("SELECT COUNT(*) FROM stock_operations", Integer.class);
    db.update(
        "UPDATE inventory_cost_allocations SET purchase_unit_cost=901 WHERE purchase_unit_cost=900");
    db.execute(
        "ALTER TABLE inventory_cost_allocations ADD CONSTRAINT test_reject_consume CHECK (purchase_unit_cost<>900)");
    try {
      assertThat(
              request(
                      "POST",
                      "/api/admin/work-orders/" + work + "/parts/use",
                      Map.of(
                          "lines", List.of(Map.of("partId", part, "quantity", "1")),
                          "reason", "강제 실패"),
                      UUID.randomUUID())
                  .getResponse()
                  .getStatus())
          .isEqualTo(409);
    } finally {
      db.execute("ALTER TABLE inventory_cost_allocations DROP CONSTRAINT test_reject_consume");
    }
    assertThat(quantity(part)).isEqualByComparingTo(before);
    assertThat(db.queryForObject("SELECT COUNT(*) FROM stock_movements", Integer.class))
        .isEqualTo(movements);
    assertThat(db.queryForObject("SELECT COUNT(*) FROM stock_operations", Integer.class))
        .isEqualTo(operations);
    assertReconciled(part);
  }

  @Test
  void legacyUseReturnCreatesUnknownAuditableLot() throws Exception {
    UUID work = work(appointment), part = part("COST-LEGACY");
    UUID actor = db.queryForObject("SELECT id FROM users WHERE email=?", UUID.class, admin);
    UUID operation = UUID.randomUUID(), use = UUID.randomUUID();
    db.update(
        "INSERT INTO stock_operations (id,actor_id,request_hash,response_body,created_at) VALUES (?,?,?,'{}',CURRENT_TIMESTAMP)",
        operation,
        actor,
        "legacy");
    db.update(
        "INSERT INTO stock_movements (id,operation_id,part_id,work_order_id,original_use_id,kind,quantity,balance_after,part_name,unit,unit_price,actor_id,reason,created_at) VALUES (?,?,?,?,NULL,'USE',1,0,'legacy','EA',20000,?,'legacy',CURRENT_TIMESTAMP)",
        use,
        operation,
        part,
        work,
        actor);
    ok(
        "POST",
        "/api/admin/work-orders/" + work + "/parts/return",
        Map.of("originalUseId", use, "quantity", "1", "reason", "legacy 반환"));
    Map<String, Object> lot =
        db.queryForMap("SELECT * FROM inventory_cost_lots WHERE part_id=?", part);
    assertThat(lot.get("origin")).isEqualTo("LEGACY_RETURN");
    assertThat(lot.get("cost_known")).isEqualTo(false);
    assertThat(lot.get("purchase_unit_cost")).isNull();
    assertThat(db.queryForObject("SELECT COUNT(*) FROM inventory_cost_allocations", Integer.class))
        .isZero();
    assertReconciled(part);
  }

  @Test
  void concurrentUseCannotDoubleConsumeAndBillingUsesSalesPrice() throws Exception {
    UUID firstWork = work(appointment);
    UUID secondWork = work(appointment("2026-09-21T03:00:00Z"));
    UUID part = part("COST-CONCURRENT");
    receipt(part, "1", "12000", UUID.randomUUID());
    var pool = Executors.newFixedThreadPool(2);
    var gate = new CountDownLatch(1);
    try {
      Callable<Integer> first =
          () -> {
            gate.await();
            return request(
                    "POST",
                    "/api/admin/work-orders/" + firstWork + "/parts/use",
                    Map.of(
                        "lines", List.of(Map.of("partId", part, "quantity", "1")),
                        "reason", "동시 사용"),
                    UUID.randomUUID())
                .getResponse()
                .getStatus();
          };
      Callable<Integer> second =
          () -> {
            gate.await();
            return request(
                    "POST",
                    "/api/admin/work-orders/" + secondWork + "/parts/use",
                    Map.of(
                        "lines", List.of(Map.of("partId", part, "quantity", "1")),
                        "reason", "동시 사용"),
                    UUID.randomUUID())
                .getResponse()
                .getStatus();
          };
      var a = pool.submit(first);
      var b = pool.submit(second);
      gate.countDown();
      assertThat(List.of(a.get(20, TimeUnit.SECONDS), b.get(20, TimeUnit.SECONDS)))
          .containsExactlyInAnyOrder(200, 409);
    } finally {
      pool.shutdownNow();
    }
    assertThat(quantity(part)).isEqualByComparingTo("0");
    assertThat(
            db.queryForObject(
                "SELECT COUNT(*) FROM inventory_cost_allocations WHERE allocation_type='CONSUME'",
                Integer.class))
        .isEqualTo(1);
    UUID billedWork =
        db.queryForObject(
            "SELECT work_order_id FROM stock_movements WHERE part_id=? AND kind='USE'",
            UUID.class,
            part);
    UUID item =
        db.queryForObject(
            "SELECT id FROM work_order_items WHERE work_order_id=?", UUID.class, billedWork);
    ok(
        "PATCH",
        "/api/admin/work-orders/" + billedWork + "/items/" + item,
        Map.of("done", true));
    ok(
        "PATCH",
        "/api/admin/work-orders/" + billedWork + "/status",
        Map.of("status", "COMPLETED"));
    JsonNode preview = read("/api/admin/billing/work-orders/" + billedWork + "/preview");
    assertThat(preview.get("total").decimalValue()).isEqualByComparingTo("40000");
    JsonNode partLine =
        java.util.stream.StreamSupport.stream(preview.get("items").spliterator(), false)
            .filter(line -> "PART".equals(line.get("kind").asText()))
            .findFirst()
            .orElseThrow();
    assertThat(partLine.get("unit_price").decimalValue()).isEqualByComparingTo("20000");
    String fingerprint = preview.get("fingerprint").asText();
    db.update(
        "UPDATE inventory_cost_lots SET purchase_unit_cost=13000 WHERE part_id=? AND cost_known=TRUE",
        part);
    JsonNode repricedCostPreview =
        read("/api/admin/billing/work-orders/" + billedWork + "/preview");
    assertThat(repricedCostPreview.get("fingerprint").asText()).isEqualTo(fingerprint);
    assertThat(repricedCostPreview.get("total").decimalValue()).isEqualByComparingTo("40000");
    JsonNode invoice =
        ok(
            "POST",
            "/api/admin/billing/work-orders/" + billedWork + "/invoices",
            Map.of("expectedFingerprint", fingerprint, "confirmZeroPrices", true));
    assertThat(invoice.get("total").decimalValue()).isEqualByComparingTo("40000");
    db.update(
        "UPDATE inventory_cost_lots SET purchase_unit_cost=14000 WHERE part_id=? AND cost_known=TRUE",
        part);
    assertThat(read("/api/admin/billing/invoices/" + invoice.get("id").asText()).get("total").decimalValue())
        .isEqualByComparingTo("40000");
    assertReconciled(part);
  }
}
