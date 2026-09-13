package com.pitflow.work;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pitflow.common.ApiException;
import com.pitflow.work.WorkRequests.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class WorkService {
  private final JdbcTemplate db;
  private final ObjectMapper json;
  private final TransactionTemplate tx;
  private final Clock clock;
  private static final BigDecimal MAX_QUANTITY = new BigDecimal("99999999999.999");

  public WorkService(
      JdbcTemplate db, ObjectMapper json, PlatformTransactionManager manager, Clock clock) {
    this.db = db;
    this.json = json;
    this.tx = new TransactionTemplate(manager);
    this.clock = clock;
    this.tx.setTimeout(15);
  }

  private OffsetDateTime now() {
    return clock.instant().atOffset(ZoneOffset.UTC);
  }

  static ApiException bad(String s) {
    return new ApiException(HttpStatus.BAD_REQUEST, s);
  }

  static ApiException conflict(String s) {
    return new ApiException(HttpStatus.CONFLICT, s);
  }

  private static ApiException missing() {
    return new ApiException(HttpStatus.NOT_FOUND, "정보를 찾을 수 없습니다.");
  }

  private static UUID id(Map<String, Object> m, String key) {
    return UUID.fromString(m.get(key).toString());
  }

  private static BigDecimal number(Map<String, Object> m, String key) {
    return new BigDecimal(m.get(key).toString());
  }

  private static boolean active(Map<String, Object> m) {
    return Boolean.TRUE.equals(m.get("active"));
  }

  private Map<String, Object> one(String sql, Object... args) {
    var rows = db.queryForList(sql, args);
    if (rows.isEmpty()) throw missing();
    return rows.get(0);
  }

  // Explicit JSON-ready mappings avoid leaking driver-specific timestamps and SQL types.
  private Map<String, Object> clean(Map<String, Object> row) {
    var out = new LinkedHashMap<String, Object>();
    row.forEach(
        (k, v) ->
            out.put(
                k.toLowerCase(Locale.ROOT),
                v instanceof Timestamp t
                    ? t.toInstant().toString()
                    : v instanceof OffsetDateTime t ? t.toString() : v));
    return out;
  }

  private List<Map<String, Object>> rows(String sql, Object... args) {
    return db.queryForList(sql, args).stream().map(this::clean).toList();
  }

  private UUID actor(String email, boolean admin) {
    var u = one("SELECT id, role FROM users WHERE email = ?", email);
    if (admin && !"ADMIN".equals(u.get("role")))
      throw new ApiException(HttpStatus.FORBIDDEN, "관리자만 변경할 수 있습니다.");
    return id(u, "id");
  }

  private String encode(Object value) {
    try {
      return json.writer()
          .with(com.fasterxml.jackson.databind.SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
          .writeValueAsString(value);
    } catch (Exception e) {
      throw new IllegalStateException("Cannot encode operation", e);
    }
  }

  private String hash(String scope, Object body) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256")
                  .digest((scope + "\n" + encode(body)).getBytes(StandardCharsets.UTF_8)));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private Map<String, Object> replay(UUID key, UUID actor, String hash) {
    var found = db.queryForList("SELECT * FROM stock_operations WHERE id = ?", key);
    if (found.isEmpty()) throw conflict("이미 등록된 정보입니다. 목록을 새로고침해 주세요.");
    var op = found.get(0);
    if (!actor.equals(id(op, "actor_id")) || !hash.equals(op.get("request_hash")))
      throw conflict("같은 요청 키로 다른 작업을 요청할 수 없습니다.");
    try {
      return json.readerFor(new TypeReference<Map<String, Object>>() {})
          .with(com.fasterxml.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
          .readValue(op.get("response_body").toString());
    } catch (Exception e) {
      throw new IllegalStateException("Invalid saved operation", e);
    }
  }

  private Map<String, Object> command(
      String email, UUID key, String scope, Object body, Supplier<Map<String, Object>> action) {
    if (key == null) throw bad("요청 키가 필요합니다.");
    UUID actor = actor(email, true);
    String hash = hash(scope, body);
    try {
      return tx.execute(
          status -> {
            // The unique insert is the concurrency arbiter. A duplicate waits for the winner.
            db.update(
                "INSERT INTO stock_operations (id,actor_id,request_hash,created_at) VALUES"
                    + " (?,?,?,?)",
                key,
                actor,
                hash,
                now());
            var response = action.get();
            db.update(
                "UPDATE stock_operations SET response_body = ? WHERE id = ?",
                encode(response),
                key);
            return response;
          });
    } catch (DuplicateKeyException e) {
      // PostgreSQL aborts a transaction on constraint failure: read only AFTER rollback.
      return replay(key, actor, hash);
    }
  }

  // Billing uses the same durable command reservation and transaction boundary as stock changes.
  public Map<String, Object> billingCommand(
      String email, UUID key, String scope, Object body, Supplier<Map<String, Object>> action) {
    return command(email, key, "billing/" + scope, body, action);
  }

  public List<Map<String, Object>> mechanics() {
    return rows("SELECT * FROM mechanics ORDER BY code");
  }

  public Map<String, Object> mechanic(String email, UUID key, UUID existing, Mechanic r) {
    return command(
        email,
        key,
        "mechanic/" + existing,
        r,
        () -> {
          UUID value = existing == null ? UUID.randomUUID() : existing;
          if (existing == null)
            db.update(
                "INSERT INTO mechanics (id,code,name,active) VALUES (?,?,?,?)",
                value,
                r.code().strip(),
                r.name().strip(),
                r.active());
          else {
            one("SELECT id FROM mechanics WHERE id = ? FOR UPDATE", value);
            db.update(
                "UPDATE mechanics SET code=?,name=?,active=? WHERE id=?",
                r.code().strip(),
                r.name().strip(),
                r.active(),
                value);
          }
          return clean(one("SELECT * FROM mechanics WHERE id=?", value));
        });
  }

  public List<Map<String, Object>> parts(boolean includeArchived) {
    var parts =
        rows(
            "SELECT * FROM parts"
                + (includeArchived ? "" : " WHERE archived=FALSE")
                + " ORDER BY sku");
    var links =
        rows(
            "SELECT r.part_id,s.id AS service_id,s.name FROM service_part_requirements r JOIN"
                + " service_items s ON s.id=r.service_id ORDER BY s.name");
    for (var p : parts)
      p.put("services", links.stream().filter(l -> id(l, "part_id").equals(id(p, "id"))).toList());
    return parts;
  }

  public Map<String, Object> part(String email, UUID key, UUID existing, Part r) {
    return command(
        email,
        key,
        "part/" + existing,
        r,
        () -> {
          UUID value = existing == null ? UUID.randomUUID() : existing;
          if (existing == null)
            db.update(
                "INSERT INTO parts (id,sku,name,unit,minimum_quantity,unit_price,active) VALUES"
                    + " (?,?,?,?,?,?,?)",
                value,
                r.sku().strip(),
                r.name().strip(),
                r.unit().name(),
                r.minimumQuantity(),
                r.unitPrice(),
                r.active());
          else {
            var before = lockPart(value);
            if (Boolean.TRUE.equals(before.get("archived"))) throw conflict("삭제한 부품은 먼저 복원해 주세요.");
            if (!r.unit().name().equals(before.get("unit")))
              throw conflict("등록 후 단위는 변경할 수 없습니다. 새 부품으로 등록해 주세요.");
            db.update(
                "UPDATE parts SET sku=?,name=?,minimum_quantity=?,unit_price=?,active=? WHERE id=?",
                r.sku().strip(),
                r.name().strip(),
                r.minimumQuantity(),
                r.unitPrice(),
                r.active(),
                value);
          }
          return clean(one("SELECT * FROM parts WHERE id=?", value));
        });
  }

  private Map<String, Object> lockPart(UUID part) {
    return one("SELECT * FROM parts WHERE id=? FOR UPDATE", part);
  }

  public Map<String, Object> archive(String email, UUID key, UUID part, Reason r, boolean restore) {
    return command(
        email,
        key,
        (restore ? "restore/" : "archive/") + part,
        r,
        () -> {
          var p = lockPart(part);
          boolean wasArchived = Boolean.TRUE.equals(p.get("archived"));
          if ((restore && !wasArchived) || (!restore && wasArchived)) return clean(p);
          if (!restore && number(p, "quantity").signum() != 0)
            throw conflict("재고가 남아 있는 부품은 삭제할 수 없습니다. 실제 사용·반환·실사 내역을 먼저 정리해 주세요.");
          // Soft deletion preserves all original movements and catalog links; restore stays
          // inactive.
          db.update("UPDATE parts SET archived=?,active=FALSE WHERE id=?", !restore, part);
          db.update(
              "INSERT INTO part_events VALUES (?,?,?,?,?)",
              UUID.randomUUID(),
              part,
              restore ? "RESTORE" : "ARCHIVE",
              r.reason().strip(),
              now());
          return clean(one("SELECT * FROM parts WHERE id=?", part));
        });
  }

  private Map<String, Object> lockWork(UUID work) {
    return one("SELECT * FROM work_orders WHERE id=? FOR UPDATE", work);
  }

  private void editable(Map<String, Object> w) {
    if (Set.of("COMPLETED", "CANCELLED").contains(w.get("status")))
      throw conflict("종료된 작업은 수정할 수 없습니다.");
  }

  private void event(UUID work, UUID actor, String type, String detail) {
    db.update(
        "INSERT INTO work_order_events (id,work_order_id,actor_id,event_type,detail,created_at)"
            + " VALUES (?,?,?,?,?,?)",
        UUID.randomUUID(),
        work,
        actor,
        type,
        detail,
        now());
    db.update("UPDATE work_orders SET updated_at=? WHERE id=?", now(), work);
  }

  public Map<String, Object> receive(String email, UUID key, Receive r) {
    return command(
        email,
        key,
        "receive",
        r,
        () -> {
          var a = one("SELECT * FROM appointments WHERE id=? FOR UPDATE", r.appointmentId());
          if (!"VISITED".equals(a.get("status"))) throw conflict("방문 처리된 예약에서 작업을 생성해 주세요.");
          if (!db.queryForList(
                  "SELECT id FROM work_orders WHERE appointment_id=?", r.appointmentId())
              .isEmpty()) throw conflict("이미 입고 처리된 예약입니다.");
          UUID vehicle = id(a, "vehicle_id");
          var car = one("SELECT * FROM vehicles WHERE id=? FOR UPDATE", vehicle);
          if (r.receivedMileage() < ((Number) car.get("mileage")).intValue())
            throw conflict("입고 주행거리가 현재 차량 주행거리보다 작습니다.");
          var m = one("SELECT * FROM mechanics WHERE id=? FOR UPDATE", r.mechanicId());
          if (!active(m)) throw conflict("활성 정비사를 배정해 주세요.");
          UUID work = UUID.randomUUID();
          db.update(
              "INSERT INTO work_orders"
                  + " (id,appointment_id,customer_id,vehicle_id,vehicle_label,plate_number,received_mileage,mechanic_id,mechanic_name,status,notes,received_at,updated_at)"
                  + " VALUES (?,?,?,?,?,?,?,?,?,'RECEIVED',?,?,?)",
              work,
              r.appointmentId(),
              a.get("customer_id"),
              vehicle,
              a.get("vehicle_label"),
              a.get("plate_number"),
              r.receivedMileage(),
              r.mechanicId(),
              m.get("name"),
              r.notes() == null ? "" : r.notes().strip(),
              now(),
              now());
          for (var item :
              db.queryForList(
                  "SELECT * FROM appointment_items WHERE appointment_id=? ORDER BY service_item_id",
                  r.appointmentId()))
            db.update(
                "INSERT INTO work_order_items"
                    + " (id,work_order_id,service_item_id,name,labor_price,duration_minutes) VALUES"
                    + " (?,?,?,?,?,?)",
                UUID.randomUUID(),
                work,
                item.get("service_item_id"),
                item.get("name"),
                item.get("labor_price"),
                item.get("duration_minutes"));
          db.update(
              "UPDATE vehicles SET mileage=?,updated_at=? WHERE id=?",
              r.receivedMileage(),
              now(),
              vehicle);
          event(
              work,
              actor(email, true),
              "RECEIVED",
              "입고 · " + r.receivedMileage() + " km · " + m.get("name"));
          return detail(email, work, true);
        });
  }

  public List<Map<String, Object>> list(String email, boolean admin) {
    UUID user = actor(email, admin);
    return admin
        ? rows("SELECT * FROM work_orders ORDER BY received_at DESC,id")
        : rows("SELECT * FROM work_orders WHERE customer_id=? ORDER BY received_at DESC,id", user);
  }

  public Map<String, Object> detail(String email, UUID work, boolean admin) {
    UUID user = actor(email, admin);
    var result =
        clean(
            admin
                ? one("SELECT * FROM work_orders WHERE id=?", work)
                : one("SELECT * FROM work_orders WHERE id=? AND customer_id=?", work, user));
    result.put(
        "items",
        rows("SELECT * FROM work_order_items WHERE work_order_id=? ORDER BY name,id", work));
    if (admin)
      result.put(
          "suggested_parts",
          rows(
              "SELECT DISTINCT p.id,p.name,p.unit,p.quantity,p.active FROM"
                  + " service_part_requirements r JOIN work_order_items i ON"
                  + " i.service_item_id=r.service_id JOIN parts p ON p.id=r.part_id WHERE"
                  + " i.work_order_id=? ORDER BY p.name",
              work));
    result.put(
        "events",
        admin
            ? rows(
                "SELECT * FROM work_order_events WHERE work_order_id=? ORDER BY created_at,id",
                work)
            : rows(
                "SELECT event_type,detail,created_at FROM work_order_events WHERE work_order_id=?"
                    + " ORDER BY created_at,id",
                work));
    // Customers see their usage, not stock levels or administrator identities.
    result.put(
        "movements",
        admin
            ? rows(
                "SELECT * FROM stock_movements WHERE work_order_id=? ORDER BY created_at,id", work)
            : rows(
                "SELECT"
                    + " id,original_use_id,kind,quantity,part_name,unit,unit_price,reason,created_at"
                    + " FROM stock_movements WHERE work_order_id=? ORDER BY created_at,id",
                work));
    return result;
  }

  public Map<String, Object> state(String email, UUID key, UUID work, State r) {
    return command(
        email,
        key,
        "state/" + work,
        r,
        () -> {
          var w = lockWork(work);
          String current = w.get("status").toString();
          String target = r.status().name();
          if (current.equals(target)) return detail(email, work, true);
          editable(w);
          var allowed =
              switch (current) {
                case "RECEIVED" -> Set.of("IN_PROGRESS", "CANCELLED");
                case "IN_PROGRESS" -> Set.of("WAITING_PARTS", "COMPLETED", "CANCELLED");
                case "WAITING_PARTS" -> Set.of("IN_PROGRESS", "CANCELLED");
                default -> Set.<String>of();
              };
          if (!allowed.contains(target)) throw conflict("허용되지 않은 작업 상태 변경입니다.");
          if (target.equals("COMPLETED")
              && db.queryForObject(
                      "SELECT COUNT(*) FROM work_order_items WHERE work_order_id=? AND done=FALSE",
                      Integer.class,
                      work)
                  > 0) throw conflict("모든 정비 항목을 완료 처리한 후 작업을 완료해 주세요.");
          if (target.equals("CANCELLED") && (r.reason() == null || r.reason().isBlank()))
            throw bad("취소 사유를 입력해 주세요.");
          db.update("UPDATE work_orders SET status=? WHERE id=?", target, work);
          if (target.equals("COMPLETED"))
            db.update("UPDATE work_orders SET completed_at=? WHERE id=?", now(), work);
          event(
              work,
              actor(email, true),
              "STATUS",
              current + " → " + target + (r.reason() == null ? "" : " · " + r.reason().strip()));
          // Cancellation never invents a physical return. Existing USE rows remain intact.
          return detail(email, work, true);
        });
  }

  public Map<String, Object> release(String email, UUID key, UUID work) {
    return command(
        email,
        key,
        "release/" + work,
        Map.of(),
        () -> {
          var order = lockWork(work);
          if (!"COMPLETED".equals(order.get("status"))) throw conflict("정비 완료 후 출고 처리해 주세요.");
          if (order.get("released_at") == null) {
            db.update("UPDATE work_orders SET released_at=? WHERE id=?", now(), work);
            event(work, actor(email, true), "RELEASED", "차량 출고 완료");
          }
          return detail(email, work, true);
        });
  }

  public Map<String, Object> assignment(String email, UUID key, UUID work, Assignment r) {
    return command(
        email,
        key,
        "assignment/" + work,
        r,
        () -> {
          editable(lockWork(work));
          var m = one("SELECT * FROM mechanics WHERE id=? FOR UPDATE", r.mechanicId());
          if (!active(m)) throw conflict("활성 정비사를 선택해 주세요.");
          db.update(
              "UPDATE work_orders SET mechanic_id=?,mechanic_name=? WHERE id=?",
              r.mechanicId(),
              m.get("name"),
              work);
          event(work, actor(email, true), "ASSIGNED", m.get("name").toString());
          return detail(email, work, true);
        });
  }

  public Map<String, Object> item(String email, UUID key, UUID work, UUID item, ItemState r) {
    return command(
        email,
        key,
        "item/" + work + "/" + item,
        r,
        () -> {
          var w = lockWork(work);
          editable(w);
          if (!"IN_PROGRESS".equals(w.get("status"))) throw conflict("진행 중인 작업에서 정비 항목을 변경해 주세요.");
          var i = one("SELECT * FROM work_order_items WHERE id=? AND work_order_id=?", item, work);
          db.update("UPDATE work_order_items SET done=? WHERE id=?", r.done(), item);
          event(
              work, actor(email, true), "ITEM", i.get("name") + " · " + (r.done() ? "완료" : "미완료"));
          return detail(email, work, true);
        });
  }

  private BigDecimal quantity(BigDecimal q) {
    if (q == null
        || q.signum() <= 0
        || q.stripTrailingZeros().scale() > 3
        || q.compareTo(MAX_QUANTITY) > 0) throw bad("수량은 0보다 큰 소수 셋째 자리까지 입력해 주세요.");
    return q.stripTrailingZeros();
  }

  private void movement(
      UUID operation,
      UUID actor,
      Map<String, Object> p,
      UUID work,
      UUID original,
      String kind,
      BigDecimal quantity,
      String reason) {
    BigDecimal delta = Set.of("USE", "ADJUST_OUT").contains(kind) ? quantity.negate() : quantity;
    BigDecimal balance = number(p, "quantity").add(delta);
    if (balance.signum() < 0) throw conflict(p.get("name") + ": 재고가 부족합니다.");
    if (balance.compareTo(MAX_QUANTITY) > 0) throw bad("최대 보관 수량을 초과합니다.");
    db.update("UPDATE parts SET quantity=? WHERE id=?", balance, p.get("id"));
    db.update(
        "INSERT INTO stock_movements"
            + " (id,operation_id,part_id,work_order_id,original_use_id,kind,quantity,balance_after,part_name,unit,unit_price,actor_id,reason,created_at)"
            + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
        UUID.randomUUID(),
        operation,
        p.get("id"),
        work,
        original,
        kind,
        quantity,
        balance,
        p.get("name"),
        p.get("unit"),
        p.get("unit_price"),
        actor,
        reason.strip(),
        now());
  }

  private Map<String, Object> operation(UUID key) {
    return Map.of(
        "operation_id",
        key,
        "movements",
        rows("SELECT * FROM stock_movements WHERE operation_id=? ORDER BY part_id,id", key));
  }

  public List<Map<String, Object>> movements(UUID part) {
    one("SELECT id FROM parts WHERE id=?", part);
    return rows("SELECT * FROM stock_movements WHERE part_id=? ORDER BY created_at DESC,id", part);
  }

  public Map<String, Object> receipt(String email, UUID key, UUID part, Quantity r) {
    BigDecimal amount = quantity(r.quantity());
    return command(
        email,
        key,
        "receipt/" + part,
        Map.of("quantity", amount, "reason", r.reason()),
        () -> {
          var p = lockPart(part);
          if (!active(p)) throw conflict("비활성 부품은 입고할 수 없습니다.");
          movement(key, actor(email, true), p, null, null, "RECEIPT", amount, r.reason());
          return operation(key);
        });
  }

  public Map<String, Object> adjust(String email, UUID key, UUID part, Adjustment r) {
    return command(
        email,
        key,
        "adjust/" + part,
        r,
        () -> {
          var p = lockPart(part);
          if (Boolean.TRUE.equals(p.get("archived"))) throw conflict("삭제한 부품은 먼저 복원해 주세요.");
          var current = number(p, "quantity");
          if (current.compareTo(r.expectedQuantity()) != 0)
            throw conflict("다른 작업으로 재고가 변경되었습니다. 새로고침 후 실사 수량을 다시 확인해 주세요.");
          var delta = r.quantity().subtract(current);
          if (delta.signum() != 0)
            movement(
                key,
                actor(email, true),
                p,
                null,
                null,
                delta.signum() > 0 ? "ADJUST_IN" : "ADJUST_OUT",
                delta.abs(),
                r.reason());
          return operation(key);
        });
  }

  public Map<String, Object> use(String email, UUID key, UUID work, Use r) {
    if (r.lines() == null || r.lines().isEmpty() || r.lines().size() > 30)
      throw bad("부품을 1~30개 선택해 주세요.");
    var lines =
        r.lines().stream()
            .map(l -> new Line(l.partId(), quantity(l.quantity())))
            .sorted(Comparator.comparing(l -> l.partId().toString()))
            .toList();
    if (lines.stream().map(Line::partId).distinct().count() != lines.size())
      throw bad("같은 부품을 중복 선택할 수 없습니다.");
    return command(
        email,
        key,
        "use/" + work,
        new Use(lines, r.reason()),
        () -> {
          var w = lockWork(work);
          if (!"IN_PROGRESS".equals(w.get("status"))) throw conflict("진행 중인 작업에서만 부품을 사용할 수 있습니다.");
          var locked = new LinkedHashMap<UUID, Map<String, Object>>();
          for (var line : lines) {
            var p = lockPart(line.partId());
            if (!active(p)) throw conflict("비활성 부품은 사용할 수 없습니다.");
            if (number(p, "quantity").compareTo(line.quantity()) < 0)
              throw conflict(p.get("name") + ": 재고가 부족합니다.");
            locked.put(line.partId(), p);
          }
          for (var line : lines)
            movement(
                key,
                actor(email, true),
                locked.get(line.partId()),
                work,
                null,
                "USE",
                line.quantity(),
                r.reason());
          return operation(key);
        });
  }

  public Map<String, Object> giveBack(String email, UUID key, UUID work, Return r) {
    BigDecimal amount = quantity(r.quantity());
    return command(
        email,
        key,
        "return/" + work,
        new Return(r.originalUseId(), amount, r.reason()),
        () -> {
          lockWork(work); // Also serializes all partial returns and state changes for this order.
          var use =
              one(
                  "SELECT * FROM stock_movements WHERE id=? AND work_order_id=? AND kind='USE'",
                  r.originalUseId(),
                  work);
          var returned =
              db.queryForObject(
                  "SELECT COALESCE(SUM(quantity),0) FROM stock_movements WHERE original_use_id=?",
                  BigDecimal.class,
                  r.originalUseId());
          if (returned.add(amount).compareTo(number(use, "quantity")) > 0)
            throw conflict("반환 수량이 남은 사용 수량보다 큽니다.");
          var p = lockPart(id(use, "part_id"));
          if (Boolean.TRUE.equals(p.get("archived"))) {
            db.update("UPDATE parts SET archived=FALSE WHERE id=?", p.get("id"));
            db.update(
                "INSERT INTO part_events VALUES (?,?,?,?,?)",
                UUID.randomUUID(),
                p.get("id"),
                "RESTORE",
                "실물 반환으로 목록 복원",
                now());
          }
          // Historical name, unit and price belong to the original use, even after catalog edits.
          p.put("name", use.get("part_name"));
          p.put("unit", use.get("unit"));
          p.put("unit_price", use.get("unit_price"));
          movement(
              key, actor(email, true), p, work, r.originalUseId(), "RETURN", amount, r.reason());
          return operation(key);
        });
  }
}
