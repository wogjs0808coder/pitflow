package com.pitflow.billing;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.pitflow.billing.BillingRequests.*;
import com.pitflow.common.ApiException;
import com.pitflow.work.WorkService;
import java.math.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
public class BillingService {
  private final JdbcTemplate db;
  private final WorkService work;
  private final ObjectMapper json;
  private final Clock clock;
  private static final BigDecimal MAX = new BigDecimal("99999999999999");

  public BillingService(JdbcTemplate db, WorkService work, ObjectMapper json, Clock clock) {
    this.db = db;
    this.work = work;
    this.json = json;
    this.clock = clock;
  }

  private OffsetDateTime now() {
    return clock.instant().atOffset(ZoneOffset.UTC);
  }

  private ApiException conflict(String m) {
    return new ApiException(HttpStatus.CONFLICT, m);
  }

  private ApiException bad(String m) {
    return new ApiException(HttpStatus.BAD_REQUEST, m);
  }

  private UUID id(Map<String, Object> r, String k) {
    return UUID.fromString(r.get(k).toString());
  }

  private BigDecimal number(Map<String, Object> r, String k) {
    return new BigDecimal(r.get(k).toString());
  }

  private Map<String, Object> one(String sql, Object... args) {
    var r = db.queryForList(sql, args);
    if (r.isEmpty()) throw new ApiException(HttpStatus.NOT_FOUND, "정보를 찾을 수 없습니다.");
    return r.get(0);
  }

  private List<Map<String, Object>> rows(String sql, Object... args) {
    return db.queryForList(sql, args).stream()
        .map(
            r -> {
              Map<String, Object> m = new LinkedHashMap<>();
              r.forEach(
                  (k, v) ->
                      m.put(
                          k.toLowerCase(Locale.ROOT),
                          v instanceof Timestamp t
                              ? t.toInstant().toString()
                              : v instanceof OffsetDateTime t ? t.toString() : v));
              return m;
            })
        .toList();
  }

  private UUID actor(String email, boolean admin) {
    var u = one("SELECT id,role FROM users WHERE email=?", email);
    if (admin && !"ADMIN".equals(u.get("role")))
      throw new ApiException(HttpStatus.FORBIDDEN, "관리자만 이용할 수 있습니다.");
    return id(u, "id");
  }

  private Map<String, Object> ownedWork(String email, UUID w, boolean admin) {
    UUID u = actor(email, admin);
    return admin
        ? one("SELECT * FROM work_orders WHERE id=?", w)
        : one("SELECT * FROM work_orders WHERE id=? AND customer_id=?", w, u);
  }

  private String fingerprint(Object lines) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256")
                  .digest(
                      json.writer()
                          .with(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
                          .writeValueAsString(lines)
                          .getBytes(StandardCharsets.UTF_8)));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private Map<String, Object> line(
      String kind, UUID source, String name, BigDecimal q, String unit, BigDecimal price) {
    BigDecimal amount = q.multiply(price).setScale(0, RoundingMode.HALF_UP);
    if (amount.compareTo(MAX) > 0) throw bad("정산 가능한 최대 금액을 초과했습니다.");
    return Map.of(
        "kind",
        kind,
        "source_id",
        source,
        "name",
        name,
        "quantity",
        q.setScale(3),
        "unit",
        unit,
        "unit_price",
        price.setScale(0),
        "amount",
        amount);
  }

  private Map<String, Object> quote(UUID w) {
    var order = one("SELECT * FROM work_orders WHERE id=?", w);
    if (!"COMPLETED".equals(order.get("status"))) throw conflict("완료된 작업만 정산할 수 있습니다.");
    var lines = new ArrayList<Map<String, Object>>();
    for (var i :
        db.queryForList("SELECT * FROM work_order_items WHERE work_order_id=? ORDER BY id", w))
      lines.add(
          line(
              "LABOR",
              id(i, "id"),
              i.get("name").toString(),
              BigDecimal.ONE,
              "JOB",
              number(i, "labor_price")));
    for (var p :
        db.queryForList(
            """
SELECT u.*,COALESCE((SELECT SUM(r.quantity) FROM stock_movements r WHERE r.original_use_id=u.id),0) AS returned
FROM stock_movements u WHERE u.work_order_id=? AND u.kind='USE' ORDER BY u.id
""",
            w)) {
      var q = number(p, "quantity").subtract(number(p, "returned"));
      if (q.signum() < 0) throw conflict("반환 기록을 확인해 주세요.");
      if (q.signum() > 0)
        lines.add(
            line(
                "PART",
                id(p, "id"),
                p.get("part_name").toString(),
                q,
                p.get("unit").toString(),
                number(p, "unit_price")));
    }
    BigDecimal total =
        lines.stream().map(l -> number(l, "amount")).reduce(BigDecimal.ZERO, BigDecimal::add);
    if (total.compareTo(MAX) > 0) throw bad("정산 가능한 최대 금액을 초과했습니다.");
    return Map.of(
        "work_order_id",
        w,
        "items",
        lines,
        "total",
        total,
        "fingerprint",
        fingerprint(lines),
        "has_zero_prices",
        lines.stream().anyMatch(l -> number(l, "unit_price").signum() == 0));
  }

  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public Map<String, Object> preview(String email, UUID w) {
    ownedWork(email, w, true);
    return quote(w);
  }

  public Map<String, Object> issue(String email, UUID key, UUID w, Issue r) {
    return work.billingCommand(
        email,
        key,
        "issue/" + w,
        r,
        () -> {
          one("SELECT id FROM work_orders WHERE id=? FOR UPDATE", w);
          var q = quote(w);
          if (!q.get("fingerprint").equals(r.expectedFingerprint()))
            throw conflict("작업 내역이 변경되었습니다. 정산 미리보기를 새로 확인해 주세요.");
          if (Boolean.TRUE.equals(q.get("has_zero_prices")) && !r.confirmZeroPrices())
            throw conflict("단가 0원 항목을 확인하고 명시적으로 승인해 주세요.");
          if (!db.queryForList("SELECT id FROM invoices WHERE active_work_order_id=?", w).isEmpty())
            throw conflict("유효한 정산 명세가 이미 있습니다.");
          UUID invoice = UUID.randomUUID();
          db.update(
              "INSERT INTO invoices"
                  + " (id,work_order_id,active_work_order_id,status,source_hash,total,issued_by,issued_at)"
                  + " VALUES (?,?,?,'OPEN',?,?,?,?)",
              invoice,
              w,
              w,
              q.get("fingerprint"),
              q.get("total"),
              actor(email, true),
              now());
          @SuppressWarnings("unchecked")
          var lines = (List<Map<String, Object>>) q.get("items");
          for (var l : lines)
            db.update(
                "INSERT INTO invoice_items VALUES (?,?,?,?,?,?,?,?,?)",
                UUID.randomUUID(),
                invoice,
                l.get("kind"),
                l.get("source_id"),
                l.get("name"),
                l.get("quantity"),
                l.get("unit"),
                l.get("unit_price"),
                l.get("amount"));
          return detail(email, invoice, true);
        });
  }

  private Map<String, Object> lockInvoice(UUID invoice) {
    var initial = one("SELECT work_order_id FROM invoices WHERE id=?", invoice);
    one("SELECT id FROM work_orders WHERE id=? FOR UPDATE", initial.get("work_order_id"));
    return one("SELECT * FROM invoices WHERE id=? FOR UPDATE", invoice);
  }

  private BigDecimal paid(UUID invoice) {
    return db.queryForObject(
        "SELECT COALESCE(SUM(CASE WHEN kind='PAYMENT' THEN amount ELSE -amount END),0) FROM"
            + " payment_records WHERE invoice_id=?",
        BigDecimal.class,
        invoice);
  }

  public Map<String, Object> collect(String email, UUID key, UUID invoice, Payment r) {
    return work.billingCommand(
        email,
        key,
        "collect/" + invoice,
        r,
        () -> {
          var i = lockInvoice(invoice);
          if (!"OPEN".equals(i.get("status"))) throw conflict("취소된 명세는 수납할 수 없습니다.");
          if (!i.get("source_hash").equals(quote(id(i, "work_order_id")).get("fingerprint")))
            throw conflict("부품 반환으로 내역이 변경되었습니다. 명세를 취소하고 다시 발행해 주세요.");
          var total = number(i, "total");
          if (total.compareTo(r.expectedTotal()) != 0) throw conflict("확인한 금액과 명세 금액이 다릅니다.");
          if (total.signum() == 0) throw conflict("0원 명세에는 수납 기록이 필요하지 않습니다.");
          if (paid(invoice).signum() != 0) throw conflict("이미 수납한 명세입니다.");
          db.update(
              """
INSERT INTO payment_records (id,invoice_id,operation_id,kind,amount,method,reference,reason,actor_id,created_at)
VALUES (?,?,?,'PAYMENT',?,?,?,'현장 수납 확인',?,?)
""",
              UUID.randomUUID(),
              invoice,
              key,
              total,
              r.method().name(),
              r.reference() == null ? "" : r.reference().strip(),
              actor(email, true),
              now());
          return detail(email, invoice, true);
        });
  }

  public Map<String, Object> reverse(String email, UUID key, UUID payment, Reason r) {
    return work.billingCommand(
        email,
        key,
        "reverse/" + payment,
        r,
        () -> {
          var original =
              one("SELECT * FROM payment_records WHERE id=? AND kind='PAYMENT'", payment);
          UUID invoice = id(original, "invoice_id");
          lockInvoice(invoice);
          if (!db.queryForList(
                  "SELECT id FROM payment_records WHERE original_payment_id=?", payment)
              .isEmpty()) throw conflict("이미 취소한 수납입니다.");
          db.update(
              """
INSERT INTO payment_records (id,invoice_id,operation_id,kind,original_payment_id,amount,method,reference,reason,actor_id,created_at)
VALUES (?,?,?,'REVERSAL',?,?,?,?,?,?,?)
""",
              UUID.randomUUID(),
              invoice,
              key,
              payment,
              original.get("amount"),
              original.get("method"),
              original.get("reference"),
              r.reason().strip(),
              actor(email, true),
              now());
          return detail(email, invoice, true);
        });
  }

  public Map<String, Object> voidInvoice(String email, UUID key, UUID invoice, Reason r) {
    return work.billingCommand(
        email,
        key,
        "void/" + invoice,
        r,
        () -> {
          var i = lockInvoice(invoice);
          if (!"OPEN".equals(i.get("status"))) throw conflict("이미 취소한 명세입니다.");
          if (paid(invoice).signum() != 0) throw conflict("수납 취소 기록을 먼저 남겨 주세요.");
          db.update(
              "UPDATE invoices SET"
                  + " status='VOID',active_work_order_id=NULL,voided_at=?,void_reason=?,voided_by=?"
                  + " WHERE id=?",
              now(),
              r.reason().strip(),
              actor(email, true),
              invoice);
          return detail(email, invoice, true);
        });
  }

  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public Map<String, Object> detail(String email, UUID invoice, boolean admin) {
    var i = one("SELECT work_order_id FROM invoices WHERE id=?", invoice);
    ownedWork(email, id(i, "work_order_id"), admin);
    var result =
        rows(
                """
SELECT i.id,i.work_order_id,i.status,i.total,i.issued_at,i.voided_at,i.void_reason,
  w.vehicle_id,w.vehicle_label,w.plate_number,w.received_mileage,w.mechanic_name,w.completed_at
FROM invoices i JOIN work_orders w ON w.id=i.work_order_id WHERE i.id=?
""",
                invoice)
            .get(0);
    result.put(
        "items",
        rows(
            "SELECT kind,source_id,name,quantity,unit,unit_price,amount FROM invoice_items WHERE"
                + " invoice_id=? ORDER BY kind,source_id",
            invoice));
    result.put(
        "payments",
        rows(
            "SELECT id,kind,original_payment_id,amount,method,reference,reason,created_at FROM"
                + " payment_records WHERE invoice_id=? ORDER BY created_at,id",
            invoice));
    result.put("paid", paid(invoice));
    result.put("balance", number(result, "total").subtract(paid(invoice)));
    if (admin)
      result.put(
          "stale",
          !one("SELECT source_hash FROM invoices WHERE id=?", invoice)
              .get("source_hash")
              .equals(quote(id(i, "work_order_id")).get("fingerprint")));
    return result;
  }

  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public List<Map<String, Object>> list(String email) {
    actor(email, true);
    return rows(
        """
SELECT i.id,i.work_order_id,i.status,i.total,i.issued_at,w.plate_number,w.vehicle_label,
  COALESCE((SELECT SUM(CASE WHEN p.kind='PAYMENT' THEN p.amount ELSE -p.amount END) FROM payment_records p WHERE p.invoice_id=i.id),0) AS paid
FROM invoices i JOIN work_orders w ON w.id=i.work_order_id ORDER BY i.issued_at DESC,i.id
""");
  }

  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public List<Map<String, Object>> history(String email, UUID vehicle) {
    UUID user = actor(email, false);
    if (vehicle != null) one("SELECT id FROM vehicles WHERE id=? AND owner_id=?", vehicle, user);
    var orders =
        vehicle == null
            ? rows(
                "SELECT"
                    + " id,vehicle_id,vehicle_label,plate_number,received_mileage,mechanic_name,status,notes,received_at,completed_at"
                    + " FROM work_orders WHERE customer_id=? AND status IN"
                    + " ('COMPLETED','CANCELLED') ORDER BY received_at DESC,id",
                user)
            : rows(
                "SELECT"
                    + " id,vehicle_id,vehicle_label,plate_number,received_mileage,mechanic_name,status,notes,received_at,completed_at"
                    + " FROM work_orders WHERE customer_id=? AND vehicle_id=? AND status IN"
                    + " ('COMPLETED','CANCELLED') ORDER BY received_at DESC,id",
                user,
                vehicle);
    for (var w : orders) {
      w.put(
          "items",
          rows(
              "SELECT name,labor_price,done FROM work_order_items WHERE work_order_id=? ORDER BY"
                  + " name",
              w.get("id")));
      w.put(
          "invoices",
          rows(
              "SELECT id,status,total,issued_at FROM invoices WHERE work_order_id=? ORDER BY"
                  + " issued_at DESC,id",
              w.get("id")));
    }
    return orders;
  }

  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public Map<String, Object> summary(String email, LocalDate from, LocalDate to) {
    actor(email, true);
    if (from == null
        || to == null
        || from.getYear() < 1900
        || to.getYear() > 2100
        || to.isBefore(from)
        || ChronoUnit.DAYS.between(from, to) > 366) throw bad("조회 기간은 1년 이내로 입력해 주세요.");
    var zone = ZoneId.of("Asia/Seoul");
    var start = from.atStartOfDay(zone).toOffsetDateTime();
    var end = to.plusDays(1).atStartOfDay(zone).toOffsetDateTime();
    var payments =
        rows(
            "SELECT kind,amount,created_at FROM payment_records WHERE created_at>=? AND"
                + " created_at<? ORDER BY created_at",
            start,
            end);
    BigDecimal received = BigDecimal.ZERO, reversed = BigDecimal.ZERO;
    var days = new TreeMap<String, BigDecimal>();
    for (var p : payments) {
      var amount = number(p, "amount");
      boolean income = p.get("kind").equals("PAYMENT");
      if (income) received = received.add(amount);
      else reversed = reversed.add(amount);
      String day =
          OffsetDateTime.parse(p.get("created_at").toString())
              .atZoneSameInstant(zone)
              .toLocalDate()
              .toString();
      days.merge(day, income ? amount : amount.negate(), BigDecimal::add);
    }
    return Map.of(
        "from",
        from,
        "to",
        to,
        "completed_count",
        db.queryForObject(
            "SELECT COUNT(*) FROM work_orders WHERE completed_at>=? AND completed_at<?",
            Integer.class,
            start,
            end),
        "received",
        received,
        "reversed",
        reversed,
        "net",
        received.subtract(reversed),
        "daily",
        days.entrySet().stream()
            .map(e -> Map.of("date", e.getKey(), "net", e.getValue()))
            .toList());
  }
}
