package com.pitflow.work;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pitflow.common.ApiException;
import com.pitflow.notification.NotificationService;
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
  private final NotificationService notificationService;
  private static final BigDecimal MAX_QUANTITY = new BigDecimal("99999999999.999");
  private static final UUID WASHER_SERVICE =
      UUID.fromString("f6b2e966-cf84-3576-9a3f-a64ebf1de473");
  private static final String WASHER_SKU = "PF-WASHER";

  public WorkService(
      JdbcTemplate db,
      ObjectMapper json,
      PlatformTransactionManager manager,
      Clock clock,
      NotificationService notificationService) {
    this.db = db;
    this.json = json;
    this.tx = new TransactionTemplate(manager);
    this.clock = clock;
    this.notificationService = notificationService;
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
    return command(actor(email, true), key, scope, body, action, null);
  }

  private Map<String, Object> command(
      UUID actor,
      UUID key,
      String scope,
      Object body,
      Supplier<Map<String, Object>> action,
      Runnable replayAuthorization) {
    if (key == null) throw bad("요청 키가 필요합니다.");
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
      if (replayAuthorization != null)
        tx.executeWithoutResult(status -> replayAuthorization.run());
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

  public List<Map<String, Object>> mechanicParts() {
    return rows(
        "SELECT id,sku,name,unit FROM parts WHERE active=TRUE AND archived=FALSE ORDER BY sku");
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
                "INSERT INTO parts"
                    + " (id,sku,name,description,unit,minimum_quantity,unit_price,active) VALUES"
                    + " (?,?,?,?,?,?,?,?)",
                value,
                r.sku().strip(),
                r.name().strip(),
                description(r.description()),
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
                "UPDATE parts SET"
                    + " sku=?,name=?,description=?,minimum_quantity=?,unit_price=?,active=? WHERE"
                    + " id=?",
                r.sku().strip(),
                r.name().strip(),
                description(r.description()),
                r.minimumQuantity(),
                r.unitPrice(),
                r.active(),
                value);
          }
          return clean(one("SELECT * FROM parts WHERE id=?", value));
        });
  }

  private static String description(String value) {
    return value == null ? "" : value.replace("\r\n", "\n").replace('\r', '\n').strip();
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

  private Map<String, Object> lockMechanicWork(UUID work, UUID actor, UUID mechanic) {
    var order = lockWork(work);
    if (order.get("mechanic_id") == null || !mechanic.equals(id(order, "mechanic_id")))
      throw missing();
    if (db.queryForList(
            """
            SELECT m.id
            FROM mechanics m JOIN users u ON u.id=m.user_id
            WHERE m.id=? AND m.user_id=? AND m.active=TRUE AND u.role='MECHANIC'
            FOR UPDATE
            """,
            mechanic,
            actor)
        .isEmpty())
      throw new ApiException(HttpStatus.FORBIDDEN, "활성 정비사 계정이 필요합니다.");
    return order;
  }

  private Map<String, Object> mutationWork(UUID work, UUID actor, UUID mechanic) {
    return mechanic == null ? lockWork(work) : lockMechanicWork(work, actor, mechanic);
  }

  private Map<String, Object> mutationDetail(UUID work, boolean admin) {
    return detail(clean(one("SELECT * FROM work_orders WHERE id=?", work)), work, admin);
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
          Map<String, Object> m = null;
          if (r.mechanicId() != null) {
            m = one("SELECT * FROM mechanics WHERE id=? FOR UPDATE", r.mechanicId());
            if (!active(m)) throw conflict("활성 정비사를 배정해 주세요.");
          }
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
              m == null ? null : m.get("name"),
              r.notes() == null ? "" : r.notes().strip(),
              now(),
              now());
          for (var item :
              db.queryForList(
                  "SELECT * FROM appointment_items WHERE appointment_id=? ORDER BY service_item_id",
                  r.appointmentId()))
            db.update(
                "INSERT INTO work_order_items"
                    + " (id,work_order_id,service_item_id,name,labor_price,duration_minutes,status,done) VALUES"
                    + " (?,?,?,?,?,?,?,FALSE)",
                UUID.randomUUID(),
                work,
                item.get("service_item_id"),
                item.get("name"),
                item.get("labor_price"),
                item.get("duration_minutes"),
                "PENDING");
          db.update(
              "UPDATE vehicles SET mileage=?,updated_at=? WHERE id=?",
              r.receivedMileage(),
              now(),
              vehicle);
          event(
              work,
              actor(email, true),
              "RECEIVED",
              "입고 · "
                  + r.receivedMileage()
                  + " km · "
                  + (m == null ? "미배정" : m.get("name")));
          if (m != null && m.get("user_id") != null) {
            notificationService.notifyWorkAssigned(id(m, "user_id"), work);
          }
          return detail(email, work, true);
        });
  }

  public List<Map<String, Object>> list(String email, boolean admin) {
    UUID user = actor(email, admin);
    return admin
        ? rows("SELECT * FROM work_orders ORDER BY received_at DESC,id")
        : rows("SELECT * FROM work_orders WHERE customer_id=? ORDER BY received_at DESC,id", user);
  }

  public List<Map<String, Object>> mechanicList(UUID mechanic) {
    return rows(
        "SELECT * FROM work_orders WHERE mechanic_id=? ORDER BY received_at DESC,id", mechanic);
  }

  public Map<String, Object> detail(String email, UUID work, boolean admin) {
    UUID user = actor(email, admin);
    var result =
        clean(
            admin
                ? one("SELECT * FROM work_orders WHERE id=?", work)
                : one("SELECT * FROM work_orders WHERE id=? AND customer_id=?", work, user));
    return detail(result, work, admin);
  }

  public Map<String, Object> mechanicDetail(UUID mechanic, UUID work) {
    var result =
        clean(one("SELECT * FROM work_orders WHERE id=? AND mechanic_id=?", work, mechanic));
    var detail = detail(result, work, false);
    detail.put(
        "suggested_parts",
        rows(
            "SELECT DISTINCT p.id,p.name,p.unit FROM"
                + " service_part_requirements r JOIN work_order_items i ON"
                + " i.service_item_id=r.service_id JOIN parts p ON p.id=r.part_id WHERE"
                + " i.work_order_id=? ORDER BY p.name",
            work));
    return detail;
  }

  public Map<String, Object> shortage(
      UUID actor, UUID mechanic, UUID key, UUID work, Shortage r) {
    BigDecimal requested = quantity(r.requestedQuantity());
    String reason = r.reason() == null ? "" : r.reason().strip();
    return command(
        actor,
        key,
        "shortage/" + work + "/" + r.workOrderItemId() + "/" + r.partId(),
        new Shortage(r.workOrderItemId(), r.partId(), requested, reason),
        () -> {
          var w = lockMechanicWork(work, actor, mechanic);
          editable(w);
          var item =
              one(
                  "SELECT * FROM work_order_items WHERE id=? AND work_order_id=?",
                  r.workOrderItemId(),
                  work);
          var part = lockPart(r.partId());
          if (!active(part) || Boolean.TRUE.equals(part.get("archived")))
            throw conflict("활성 부품을 선택해 주세요.");
          if (!db.queryForList(
                  "SELECT id FROM part_shortage_reports WHERE work_order_id=? AND work_order_item_id=? AND part_id=? AND status='OPEN'",
                  work,
                  r.workOrderItemId(),
                  r.partId())
              .isEmpty()) throw conflict("이미 접수된 부품 부족 신고가 있습니다.");
          UUID id = UUID.randomUUID();
          db.update(
              "INSERT INTO part_shortage_reports"
                  + " (id,work_order_id,work_order_item_id,part_id,reporter_user_id,requested_quantity,available_quantity_snapshot,reason,status,open_guard,created_at)"
                  + " VALUES (?,?,?,?,?,?,?,?,?,?,?)",
              id,
              work,
              item.get("id"),
              part.get("id"),
              actor,
              requested,
              part.get("quantity"),
              reason,
              "OPEN",
              "Y",
              now());
          notificationService.notifyPartShortage(work);
          var response = shortage(id);
          response.remove("available_quantity_snapshot");
          response.remove("reporter_user_id");
          response.remove("resolved_by_user_id");
          response.remove("open_guard");
          return response;
        },
        () -> lockMechanicWork(work, actor, mechanic));
  }

  public List<Map<String, Object>> shortages(boolean openOnly) {
    return rows(
        "SELECT r.*,w.vehicle_label,w.plate_number,w.mechanic_name,i.name AS item_name,p.sku,p.name AS part_name,"
            + " reporter.name AS reporter_name,resolver.name AS resolver_name"
            + " FROM part_shortage_reports r JOIN work_orders w ON w.id=r.work_order_id"
            + " JOIN work_order_items i ON i.id=r.work_order_item_id JOIN parts p ON p.id=r.part_id"
            + " JOIN users reporter ON reporter.id=r.reporter_user_id"
            + " LEFT JOIN users resolver ON resolver.id=r.resolved_by_user_id"
            + (openOnly ? " WHERE r.status='OPEN'" : "")
            + " ORDER BY CASE WHEN r.status='OPEN' THEN 0 ELSE 1 END,r.created_at DESC,r.id");
  }

  public Map<String, Object> resolveShortage(String email, UUID key, UUID reportId) {
    return command(
        email,
        key,
        "shortage-resolve/" + reportId,
        Map.of("reportId", reportId),
        () -> {
          UUID resolver = actor(email, true);
          var report = one("SELECT * FROM part_shortage_reports WHERE id=? FOR UPDATE", reportId);
          if ("RESOLVED".equals(report.get("status"))) return shortage(reportId);
          db.update(
              "UPDATE part_shortage_reports SET status='RESOLVED',open_guard=NULL,resolved_at=?,resolved_by_user_id=? WHERE id=?",
              now(),
              resolver,
              reportId);
          return shortage(reportId);
        });
  }

  private Map<String, Object> shortage(UUID reportId) {
    return clean(
        one(
            "SELECT r.*,w.vehicle_label,w.plate_number,w.mechanic_name,i.name AS item_name,p.sku,p.name AS part_name,"
                + " reporter.name AS reporter_name,resolver.name AS resolver_name"
                + " FROM part_shortage_reports r JOIN work_orders w ON w.id=r.work_order_id"
                + " JOIN work_order_items i ON i.id=r.work_order_item_id JOIN parts p ON p.id=r.part_id"
                + " JOIN users reporter ON reporter.id=r.reporter_user_id"
                + " LEFT JOIN users resolver ON resolver.id=r.resolved_by_user_id WHERE r.id=?",
            reportId));
  }

  private Map<String, Object> detail(Map<String, Object> result, UUID work, boolean admin) {
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
    // Non-admin readers see work usage, not warehouse levels or administrator identities.
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
    return state(actor(email, true), null, key, work, r, true);
  }

  public Map<String, Object> mechanicState(
      UUID actor, UUID mechanic, UUID key, UUID work, State r) {
    return state(actor, mechanic, key, work, r, false);
  }

  private Map<String, Object> state(
      UUID actor, UUID mechanic, UUID key, UUID work, State r, boolean adminResponse) {
    return command(
        actor,
        key,
        "state/" + work,
        r,
        () -> {
          var w = mutationWork(work, actor, mechanic);
          String current = w.get("status").toString();
          String target = r.status().name();
          if (current.equals(target)) return mutationDetail(work, adminResponse);
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
                      "SELECT COUNT(*) FROM work_order_items WHERE work_order_id=?"
                          + " AND status NOT IN ('COMPLETED','SKIPPED')",
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
              actor,
              "STATUS",
              current + " → " + target + (r.reason() == null ? "" : " · " + r.reason().strip()));
          if (mechanic != null && target.equals("COMPLETED")) {
            notificationService.notifyWorkCompletedToAdmins(work);
          }
          // Cancellation never invents a physical return. Existing USE rows remain intact.
          return mutationDetail(work, adminResponse);
        },
        mechanic == null ? null : () -> lockMechanicWork(work, actor, mechanic));
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
          var currentWork = lockWork(work);
          editable(currentWork);
          if (r.mechanicId() == null) {
            db.update(
                "UPDATE work_orders SET mechanic_id=NULL,mechanic_name=NULL WHERE id=?", work);
            event(work, actor(email, true), "UNASSIGNED", "담당 정비사 배정 해제");
            return detail(email, work, true);
          }
          var m = one("SELECT * FROM mechanics WHERE id=? FOR UPDATE", r.mechanicId());
          if (!active(m)) throw conflict("활성 정비사를 선택해 주세요.");
          db.update(
              "UPDATE work_orders SET mechanic_id=?,mechanic_name=? WHERE id=?",
              r.mechanicId(),
              m.get("name"),
              work);
          event(work, actor(email, true), "ASSIGNED", m.get("name").toString());
          if (m.get("user_id") != null
              && !Objects.equals(currentWork.get("mechanic_id"), r.mechanicId())) {
            notificationService.notifyWorkAssigned(id(m, "user_id"), work);
          }
          return detail(email, work, true);
        });
  }

  public Map<String, Object> item(String email, UUID key, UUID work, UUID item, ItemState r) {
    return item(actor(email, true), null, key, work, item, r, true);
  }

  public Map<String, Object> mechanicItem(
      UUID actor, UUID mechanic, UUID key, UUID work, UUID item, ItemState r) {
    return item(actor, mechanic, key, work, item, r, false);
  }

  private Map<String, Object> item(
      UUID actor,
      UUID mechanic,
      UUID key,
      UUID work,
      UUID item,
      ItemState r,
      boolean adminResponse) {
    return command(
        actor,
        key,
        "item/" + work + "/" + item,
        r,
        () -> {
          var w = mutationWork(work, actor, mechanic);
          editable(w);
          if (!"IN_PROGRESS".equals(w.get("status"))) throw conflict("진행 중인 작업에서 정비 항목을 변경해 주세요.");
          var i = one("SELECT * FROM work_order_items WHERE id=? AND work_order_id=?", item, work);
          boolean hasStatus = r.status() != null;
          boolean hasDone = r.done() != null;
          if (hasStatus == hasDone) throw bad("status 또는 done 중 하나만 입력해 주세요.");
          String target = hasStatus ? r.status().name() : (r.done() ? "COMPLETED" : "IN_PROGRESS");
          String current = i.get("status") == null ? (Boolean.TRUE.equals(i.get("done")) ? "COMPLETED" : "PENDING") : i.get("status").toString();
          if (current.equals(target)) return mutationDetail(work, adminResponse);
          var allowed = switch (current) {
            case "PENDING", "IN_PROGRESS" -> Set.of("PENDING", "IN_PROGRESS", "COMPLETED", "WAITING_PARTS", "SKIPPED");
            case "WAITING_PARTS" -> Set.of("IN_PROGRESS", "COMPLETED", "SKIPPED");
            case "COMPLETED", "SKIPPED" -> Set.of("IN_PROGRESS");
            default -> Set.<String>of();
          };
          if (!allowed.contains(target)) throw conflict("허용되지 않는 정비 항목 상태 변경입니다.");
          String reason = r.reason() == null ? null : r.reason().strip();
          if (target.equals("SKIPPED") && (reason == null || reason.isBlank())) throw bad("건너뛴 사유를 입력해 주세요.");
          db.update("UPDATE work_order_items SET status=?,done=?,skip_reason=? WHERE id=?", target, target.equals("COMPLETED"), target.equals("SKIPPED") ? reason : null, item);
          event(work, actor, "ITEM", i.get("name") + " · " + current + " → " + target + (target.equals("SKIPPED") ? " · " + reason : ""));
          return mutationDetail(work, adminResponse);
        },
        mechanic == null ? null : () -> lockMechanicWork(work, actor, mechanic));
  }

  private BigDecimal quantity(BigDecimal q) {
    if (q == null
        || q.signum() <= 0
        || q.stripTrailingZeros().scale() > 3
        || q.compareTo(MAX_QUANTITY) > 0) throw bad("수량은 0보다 큰 소수 셋째 자리까지 입력해 주세요.");
    return q.stripTrailingZeros();
  }
  private void validateUnitQuantity(Map<String, Object> part, BigDecimal quantity) {
    if ("EA".equals(part.get("unit"))
        && quantity.stripTrailingZeros().scale() > 0) {
      throw bad("EA 단위 부품은 정수 수량만 입력할 수 있습니다.");
    }
  }
  private void validateWasherUse(UUID work, Map<String, Object> part) {
    if (!WASHER_SKU.equals(part.get("sku"))) return;

    Integer washerServiceCount =
        db.queryForObject(
            """
            SELECT COUNT(*)
            FROM work_order_items
            WHERE work_order_id=?
              AND service_item_id=?
            """,
            Integer.class,
            work,
            WASHER_SERVICE);

    if (washerServiceCount == null || washerServiceCount == 0) {
      throw conflict("워셔액은 워셔액 보충 서비스가 포함된 작업에서만 사용할 수 있습니다.");
    }

    Integer previousUses =
        db.queryForObject(
            """
            SELECT COUNT(*)
            FROM stock_movements
            WHERE work_order_id=?
              AND part_id=?
              AND kind='USE'
            """,
            Integer.class,
            work,
            part.get("id"));

    if (previousUses != null && previousUses > 0) {
      throw conflict("워셔액 보충은 한 작업에서 한 번만 기록할 수 있습니다.");
    }
  }
  private BigDecimal purchaseCost(BigDecimal value) {
    if (value == null) return null;
    if (value.signum() < 0
        || value.stripTrailingZeros().scale() > 3
        || value.compareTo(MAX_QUANTITY) > 0)
      throw bad("매입 단가는 0 이상인 소수 셋째 자리까지 입력해 주세요.");
    return value.stripTrailingZeros();
  }

  private UUID movement(
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
    UUID movement = UUID.randomUUID();
    db.update(
        "INSERT INTO stock_movements"
            + " (id,operation_id,part_id,work_order_id,original_use_id,kind,quantity,balance_after,part_name,unit,unit_price,actor_id,reason,created_at)"
            + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
        movement,
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
    return movement;
  }

  private void createCostLot(
      UUID part,
      UUID sourceMovement,
      String origin,
      BigDecimal quantity,
      BigDecimal unitCost) {
    var timestamp = now();
    db.update(
        "INSERT INTO inventory_cost_lots"
            + " (id,part_id,source_movement_id,origin,original_quantity,remaining_quantity,purchase_unit_cost,cost_known,received_at,created_at)"
            + " VALUES (?,?,?,?,?,?,?,?,?,?)",
        UUID.randomUUID(),
        part,
        sourceMovement,
        origin,
        quantity,
        quantity,
        unitCost,
        unitCost != null,
        timestamp,
        timestamp);
  }

  private void consumeCostLots(UUID movement, UUID part, BigDecimal quantity) {
    BigDecimal remaining = quantity;
    var lots =
        db.queryForList(
            "SELECT * FROM inventory_cost_lots WHERE part_id=? AND remaining_quantity>0"
                + " ORDER BY received_at,id FOR UPDATE",
            part);
    for (var lot : lots) {
      if (remaining.signum() == 0) break;
      BigDecimal available = number(lot, "remaining_quantity");
      BigDecimal allocated = available.min(remaining);
      db.update(
          "UPDATE inventory_cost_lots SET remaining_quantity=? WHERE id=?",
          available.subtract(allocated),
          lot.get("id"));
      db.update(
          "INSERT INTO inventory_cost_allocations"
              + " (id,movement_id,lot_id,allocation_type,source_allocation_id,quantity,purchase_unit_cost,cost_known,created_at)"
              + " VALUES (?,?,?,'CONSUME',NULL,?,?,?,?)",
          UUID.randomUUID(),
          movement,
          lot.get("id"),
          allocated,
          lot.get("purchase_unit_cost"),
          lot.get("cost_known"),
          now());
      remaining = remaining.subtract(allocated);
    }
    if (remaining.signum() != 0)
      throw conflict("재고 원가 lot 수량이 실제 재고와 일치하지 않습니다.");
  }

  private void restoreCostLots(UUID returnMovement, UUID originalUse, UUID part, BigDecimal quantity) {
    var allocations =
        db.queryForList(
            "SELECT a.*,l.received_at AS lot_received_at FROM inventory_cost_allocations a"
                + " JOIN inventory_cost_lots l ON l.id=a.lot_id"
                + " WHERE a.movement_id=? AND a.allocation_type='CONSUME'"
                + " ORDER BY l.received_at DESC,l.id DESC,a.id DESC FOR UPDATE",
            originalUse);
    if (allocations.isEmpty()) {
      createCostLot(part, returnMovement, "LEGACY_RETURN", quantity, null);
      return;
    }
    BigDecimal remaining = quantity;
    for (var allocation : allocations) {
      if (remaining.signum() == 0) break;
      BigDecimal restored =
          db.queryForObject(
              "SELECT COALESCE(SUM(quantity),0) FROM inventory_cost_allocations"
                  + " WHERE source_allocation_id=? AND allocation_type='RESTORE'",
              BigDecimal.class,
              allocation.get("id"));
      BigDecimal available = number(allocation, "quantity").subtract(restored);
      if (available.signum() <= 0) continue;
      BigDecimal amount = available.min(remaining);
      var lot = one("SELECT * FROM inventory_cost_lots WHERE id=? FOR UPDATE", allocation.get("lot_id"));
      db.update(
          "UPDATE inventory_cost_lots SET remaining_quantity=? WHERE id=?",
          number(lot, "remaining_quantity").add(amount),
          lot.get("id"));
      db.update(
          "INSERT INTO inventory_cost_allocations"
              + " (id,movement_id,lot_id,allocation_type,source_allocation_id,quantity,purchase_unit_cost,cost_known,created_at)"
              + " VALUES (?,?,?,'RESTORE',?,?,?,?,?)",
          UUID.randomUUID(),
          returnMovement,
          lot.get("id"),
          allocation.get("id"),
          amount,
          allocation.get("purchase_unit_cost"),
          allocation.get("cost_known"),
          now());
      remaining = remaining.subtract(amount);
    }
    if (remaining.signum() != 0)
      throw conflict("반환할 원가 allocation 수량을 확인해 주세요.");
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
    BigDecimal unitCost = purchaseCost(r.purchaseUnitCost());
    var request = new LinkedHashMap<String, Object>();
    request.put("quantity", amount);
    request.put("reason", r.reason());
    request.put("purchaseUnitCost", unitCost);
    return command(
        email,
        key,
        "receipt/" + part,
        request,
        () -> {
          var p = lockPart(part);
          if (!active(p)) throw conflict("비활성 부품은 입고할 수 없습니다.");
          UUID movement =
              movement(key, actor(email, true), p, null, null, "RECEIPT", amount, r.reason());
          createCostLot(part, movement, "RECEIPT", amount, unitCost);
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
          if (delta.signum() != 0) {
            UUID movement =
                movement(
                key,
                actor(email, true),
                p,
                null,
                null,
                delta.signum() > 0 ? "ADJUST_IN" : "ADJUST_OUT",
                delta.abs(),
                r.reason());
            if (delta.signum() > 0)
              createCostLot(part, movement, "ADJUST_IN", delta, null);
            else consumeCostLots(movement, part, delta.abs());
          }
          return operation(key);
        });
  }

  public Map<String, Object> use(String email, UUID key, UUID work, Use r) {
    return use(actor(email, true), null, key, work, r);
  }

  public Map<String, Object> mechanicUse(
      UUID actor, UUID mechanic, UUID key, UUID work, Use r) {
    return use(actor, mechanic, key, work, r);
  }

  private Map<String, Object> use(UUID actor, UUID mechanic, UUID key, UUID work, Use r) {
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
        actor,
        key,
        "use/" + work,
        new Use(lines, r.reason()),
        () -> {
          var w = mutationWork(work, actor, mechanic);
          if (!"IN_PROGRESS".equals(w.get("status"))) throw conflict("진행 중인 작업에서만 부품을 사용할 수 있습니다.");
          var locked = new LinkedHashMap<UUID, Map<String, Object>>();
          for (var line : lines) {
            var p = lockPart(line.partId());
            validateUnitQuantity(p, line.quantity());
            validateWasherUse(work, p);

            if (!active(p)) throw conflict("비활성 부품은 사용할 수 없습니다.");
            if (number(p, "quantity").compareTo(line.quantity()) < 0)
              throw conflict(p.get("name") + ": 재고가 부족합니다.");
            locked.put(line.partId(), p);
          }
          for (var line : lines) {
            UUID movement =
                movement(
                key,
                actor,
                locked.get(line.partId()),
                work,
                null,
                "USE",
                line.quantity(),
                r.reason());
            consumeCostLots(movement, line.partId(), line.quantity());
          }
          return operation(key);
        },
        mechanic == null ? null : () -> lockMechanicWork(work, actor, mechanic));
  }

  public Map<String, Object> giveBack(String email, UUID key, UUID work, Return r) {
    return giveBack(actor(email, true), null, key, work, r);
  }

  public Map<String, Object> mechanicGiveBack(
      UUID actor, UUID mechanic, UUID key, UUID work, Return r) {
    return giveBack(actor, mechanic, key, work, r);
  }

  private Map<String, Object> giveBack(
      UUID actor, UUID mechanic, UUID key, UUID work, Return r) {
    BigDecimal amount = quantity(r.quantity());
    return command(
        actor,
        key,
        "return/" + work,
        new Return(r.originalUseId(), amount, r.reason()),
        () -> {
          mutationWork(work, actor, mechanic);
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
          UUID movement =
              movement(
                  key, actor, p, work, r.originalUseId(), "RETURN", amount, r.reason());
          restoreCostLots(movement, r.originalUseId(), id(use, "part_id"), amount);
          return operation(key);
        },
        mechanic == null ? null : () -> lockMechanicWork(work, actor, mechanic));
  }
}
