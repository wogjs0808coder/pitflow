package com.pitflow.billing;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.pitflow.billing.BillingRequests.*;
import com.pitflow.common.ApiException;
import com.pitflow.finance.TreasuryMutationService;
import com.pitflow.work.WorkService;
import java.math.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class BillingService {
  private final JdbcTemplate db;
  private final WorkService work;
  private final ObjectMapper json;
  private final Clock clock;
  private final TreasuryMutationService treasury;
  private final TossPaymentGateway toss;
  private final TransactionTemplate tx;
  private static final BigDecimal MAX = new BigDecimal("99999999999999");

  public BillingService(
      JdbcTemplate db,
      WorkService work,
      ObjectMapper json,
      Clock clock,
      TreasuryMutationService treasury,
      TossPaymentGateway toss,
      PlatformTransactionManager manager) {
    this.db = db;
    this.work = work;
    this.json = json;
    this.clock = clock;
    this.treasury = treasury;
    this.toss = toss;
    this.tx = new TransactionTemplate(manager);
    this.tx.setTimeout(15);
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
    var complimentarySources = new HashSet<UUID>();
    for (var i :
        db.queryForList("SELECT * FROM work_order_items WHERE work_order_id=? AND status='COMPLETED' ORDER BY id", w))
      lines.add(
          line(
              "LABOR",
              id(i, "id"),
              i.get("name").toString(),
              number(i, "quantity"),
              "JOB",
              number(i, "labor_price")));
    for (var p :
        db.queryForList(
            """
SELECT u.*,
  COALESCE((SELECT SUM(r.quantity) FROM stock_movements r WHERE r.original_use_id=u.id),0) AS returned,
  EXISTS (
    SELECT 1
    FROM work_orders wo
    JOIN appointment_item_parts aip
      ON aip.appointment_id=wo.appointment_id
     AND aip.part_id=u.part_id
     AND aip.charge_policy='COMPLIMENTARY'
    WHERE wo.id=u.work_order_id
  ) AS complimentary
FROM stock_movements u
WHERE u.work_order_id=? AND u.kind='USE'
ORDER BY u.id
""",
            w)) {
      var q = number(p, "quantity").subtract(number(p, "returned"));
      if (q.signum() < 0) throw conflict("반환 기록을 확인해 주세요.");
      if (q.signum() > 0) {
        boolean complimentary = Boolean.TRUE.equals(p.get("complimentary"));
        UUID source = id(p, "id");
        if (complimentary) complimentarySources.add(source);
        lines.add(
            line(
                "PART",
                source,
                p.get("part_name").toString(),
                q,
                p.get("unit").toString(),
                complimentary ? BigDecimal.ZERO : number(p, "unit_price")));
      }
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
        lines.stream()
            .anyMatch(
                l ->
                    number(l, "unit_price").signum() == 0
                        && !complimentarySources.contains(id(l, "source_id"))));
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

  public Map<String, Object> prepareToss(String email, UUID key, UUID invoice) {
    return prepareToss(email, key, invoice, false);
  }

  public Map<String, Object> prepareTossForAdmin(String email, UUID key, UUID invoice) {
    return prepareToss(email, key, invoice, true);
  }

  private Map<String, Object> prepareToss(
      String email, UUID key, UUID invoice, boolean admin) {
    if (!toss.configured())
      throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "Toss 테스트 결제가 설정되지 않았습니다.");
    Supplier<Map<String, Object>> action =
        () -> {
          var i = lockInvoice(invoice);
          var orderWork = ownedWork(email, id(i, "work_order_id"), admin);
          if (!"OPEN".equals(i.get("status"))) throw conflict("취소된 명세는 결제할 수 없습니다.");
          if (!i.get("source_hash").equals(quote(id(i, "work_order_id")).get("fingerprint")))
            throw conflict("정비 내역이 변경되었습니다. 관리자에게 명세 재발행을 요청해 주세요.");
          BigDecimal total = number(i, "total");
          BigDecimal outstanding = total.subtract(paid(invoice));
          if (total.signum() <= 0) throw conflict("0원 명세는 결제할 수 없습니다.");
          if (outstanding.signum() <= 0) throw conflict("이미 수납한 명세입니다.");
          if (outstanding.compareTo(total) != 0)
            throw conflict("현재 정산은 남은 금액의 전액 수납만 지원합니다.");
          UUID customer = id(orderWork, "customer_id");
          var customerIdentity = one("SELECT email,name FROM users WHERE id=?", customer);
          var existing =
              db.queryForList(
                  "SELECT * FROM payment_provider_orders WHERE invoice_id=?"
                      + " AND status IN ('READY','CONFIRMING') ORDER BY created_at DESC,id DESC",
                  invoice);
          Map<String, Object> order;
          if (existing.isEmpty()) {
            String orderId = "pitflow_" + UUID.randomUUID().toString().replace("-", "");
            String customerKey = "customer_" + customer.toString().replace("-", "");
            UUID id = UUID.randomUUID();
            db.update(
                "INSERT INTO payment_provider_orders"
                    + " (id,invoice_id,customer_id,provider,provider_order_id,customer_key,amount,status,created_at,updated_at)"
                    + " VALUES (?,?,?,'TOSS',?,?,?,'READY',?,?)",
                id, invoice, customer, orderId, customerKey, total, now(), now());
            order = one("SELECT * FROM payment_provider_orders WHERE id=?", id);
          } else {
            order = existing.get(0);
            if (!Set.of("READY", "CONFIRMING").contains(order.get("status").toString()))
              throw conflict("이미 처리된 결제 주문입니다.");
          }
          return Map.of(
              "clientKey", toss.clientKey(),
              "invoiceId", invoice,
              "orderId", order.get("provider_order_id"),
              "customerKey", order.get("customer_key"),
              "customerEmail", customerIdentity.get("email"),
              "customerName", customerIdentity.get("name"),
              "amount", outstanding,
              "orderName", "PitFlow 정비 정산");
        };
    return (admin ? work.billingCommand(
            email,
            key,
            "toss-order/" + invoice,
            Map.of("invoiceId", invoice),
            action)
        : work.customerBillingCommand(
        email,
        key,
        "toss-order/" + invoice,
        Map.of("invoiceId", invoice),
        action));
  }

  private record ProviderReservation(Map<String, Object> order, boolean claimed) {}

  public Map<String, Object> confirmToss(String email, UUID key, TossConfirm request) {
    return confirmToss(email, key, request, false);
  }

  public Map<String, Object> confirmTossForAdmin(
      String email, UUID key, TossConfirm request) {
    return confirmToss(email, key, request, true);
  }

  private Map<String, Object> confirmToss(
      String email, UUID key, TossConfirm request, boolean admin) {
    actor(email, admin);
    ProviderReservation reservation =
        tx.execute(
            status -> {
              var order =
                  admin
                      ? one(
                          "SELECT po.* FROM payment_provider_orders po"
                              + " WHERE po.provider_order_id=? AND po.invoice_id=? FOR UPDATE",
                          request.orderId(), request.invoiceId())
                      : one(
                          "SELECT po.* FROM payment_provider_orders po"
                              + " JOIN invoices i ON i.id=po.invoice_id"
                              + " JOIN work_orders w ON w.id=i.work_order_id"
                              + " JOIN users u ON u.id=w.customer_id"
                              + " WHERE po.provider_order_id=? AND po.invoice_id=? AND u.email=? FOR UPDATE",
                          request.orderId(), request.invoiceId(), email);
              if (number(order, "amount").compareTo(request.amount()) != 0)
                throw conflict("결제 금액이 명세 금액과 다릅니다.");
              String state = order.get("status").toString();
              if ("DONE".equals(state)) return new ProviderReservation(order, false);
              if (!Set.of("READY", "CONFIRMING").contains(state))
                throw conflict("결제할 수 없는 주문 상태입니다.");
              boolean claimed = "READY".equals(state);
              if (claimed)
                db.update(
                    "UPDATE payment_provider_orders SET status='CONFIRMING',provider_payment_key=?,updated_at=? WHERE id=?",
                    request.paymentKey(), now(), order.get("id"));
              else if (order.get("provider_payment_key") != null
                  && !request.paymentKey().equals(order.get("provider_payment_key").toString()))
                throw conflict("다른 결제 키로 처리 중인 주문입니다.");
              order.put("provider_payment_key", request.paymentKey());
              return new ProviderReservation(order, claimed);
            });
    if (reservation == null) throw new IllegalStateException("Missing payment reservation");
    if ("DONE".equals(reservation.order().get("status")))
      return detail(email, request.invoiceId(), admin);

    TossPaymentGateway.Payment approved;
    try {
      approved =
          reservation.claimed()
              ? toss.confirm(request.paymentKey(), request.orderId(), request.amount())
              : toss.lookup(request.paymentKey());
    } catch (RuntimeException failure) {
      try {
        approved = toss.lookup(request.paymentKey());
      } catch (RuntimeException lookupFailure) {
        throw failure;
      }
    }
    validateProviderPayment(approved, request.paymentKey(), request.orderId(), request.amount(), "DONE");
    TossPaymentGateway.Payment result = approved;
    Supplier<Map<String, Object>> action =
        () -> {
          var order = one("SELECT * FROM payment_provider_orders WHERE provider_order_id=? FOR UPDATE", request.orderId());
          if ("DONE".equals(order.get("status"))) return detail(email, request.invoiceId(), admin);
          var invoice = lockInvoice(request.invoiceId());
          var orderWork = ownedWork(email, id(invoice, "work_order_id"), admin);
          if (!"OPEN".equals(invoice.get("status"))) throw conflict("취소된 명세는 결제할 수 없습니다.");
          BigDecimal outstanding = number(invoice, "total").subtract(paid(request.invoiceId()));
          if (outstanding.signum() <= 0) throw conflict("이미 수납한 명세입니다.");
          if (outstanding.compareTo(request.amount()) != 0)
            throw conflict("결제 금액이 현재 미수금과 다릅니다.");
          UUID payment = UUID.randomUUID();
          UUID customer = id(orderWork, "customer_id");
          db.update(
              "INSERT INTO payment_records (id,invoice_id,operation_id,kind,amount,method,reference,reason,actor_id,created_at)"
                  + " VALUES (?,?,?,'PAYMENT',?,'CARD',?,'Toss 테스트 결제',?,?)",
              payment, request.invoiceId(), key, request.amount(), request.orderId(), customer, now());
          treasury.changeOperating(request.amount(), "CUSTOMER_PAYMENT", "Toss 고객 결제",
              "PAYMENT_RECORD", payment, customer);
          db.update(
              "UPDATE payment_provider_orders SET status='DONE',provider_payment_key=?,payment_record_id=?,approved_at=?,updated_at=? WHERE id=?",
              result.paymentKey(), payment, now(), now(), order.get("id"));
          return detail(email, request.invoiceId(), admin);
        };
    return admin
        ? work.billingCommand(
            email,
            key,
            "toss-confirm/" + request.orderId(),
            request,
            action)
        : work.customerBillingCommand(
        email,
        key,
        "toss-confirm/" + request.orderId(),
        request,
        action);
  }

  private void validateProviderPayment(
      TossPaymentGateway.Payment payment, String paymentKey, String orderId, BigDecimal amount, String status) {
    if (!paymentKey.equals(payment.paymentKey())
        || !orderId.equals(payment.orderId())
        || amount.compareTo(payment.totalAmount()) != 0
        || !status.equals(payment.status()))
      throw conflict("결제사 승인 정보가 요청한 명세와 일치하지 않습니다.");
  }

  public Map<String, Object> refundToss(String email, UUID key, UUID payment, Reason reason) {
    Map<String, Object> order =
        tx.execute(
            status -> {
              actor(email, true);
              var row = one("SELECT * FROM payment_provider_orders WHERE payment_record_id=? FOR UPDATE", payment);
              if ("CANCELED".equals(row.get("status"))) return row;
              if (!Set.of("DONE", "REFUNDING").contains(row.get("status").toString()))
                throw conflict("환불할 수 없는 결제 상태입니다.");
              db.update("UPDATE payment_provider_orders SET status='REFUNDING',updated_at=? WHERE id=?", now(), row.get("id"));
              return row;
            });
    if (order == null) throw new IllegalStateException("Missing refund reservation");
    UUID invoice = id(order, "invoice_id");
    if ("CANCELED".equals(order.get("status")))
      return work.billingCommand(
          email,
          key,
          "toss-refund/" + payment,
          reason,
          () -> {
            throw conflict("이미 취소한 수납입니다.");
          });
    String paymentKey = order.get("provider_payment_key").toString();
    TossPaymentGateway.Payment cancelled;
    try {
      cancelled = toss.cancel(paymentKey, reason.reason().strip(), key);
    } catch (RuntimeException failure) {
      try {
        cancelled = toss.lookup(paymentKey);
      } catch (RuntimeException lookupFailure) {
        throw failure;
      }
    }
    validateProviderPayment(cancelled, paymentKey, order.get("provider_order_id").toString(),
        number(order, "amount"), "CANCELED");
    return work.billingCommand(
        email,
        key,
        "toss-refund/" + payment,
        reason,
        () -> {
          var locked = one("SELECT * FROM payment_provider_orders WHERE payment_record_id=? FOR UPDATE", payment);
          if ("CANCELED".equals(locked.get("status"))) return detail(email, invoice, true);
          var original = one("SELECT * FROM payment_records WHERE id=? AND kind='PAYMENT'", payment);
          lockInvoice(invoice);
          if (!db.queryForList("SELECT id FROM payment_records WHERE original_payment_id=?", payment).isEmpty())
            throw conflict("이미 취소한 수납입니다.");
          UUID reversal = UUID.randomUUID();
          UUID admin = actor(email, true);
          db.update(
              "INSERT INTO payment_records (id,invoice_id,operation_id,kind,original_payment_id,amount,method,reference,reason,actor_id,created_at)"
                  + " VALUES (?,?,?,'REVERSAL',?,?,?,?,?,?,?)",
              reversal, invoice, key, payment, original.get("amount"), original.get("method"),
              original.get("reference"), reason.reason().strip(), admin, now());
          treasury.changeOperating(number(original, "amount").negate(), "PAYMENT_REFUND",
              "Toss 결제 취소: " + reason.reason().strip(), "PAYMENT_RECORD", reversal, admin);
          db.update("UPDATE payment_provider_orders SET status='CANCELED',cancelled_at=?,updated_at=? WHERE id=?",
              now(), now(), locked.get("id"));
          return detail(email, invoice, true);
        });
  }

  public Map<String, Object> collect(String email, UUID key, UUID invoice, Payment r) {
    return work.billingCommand(
        email,
        key,
        "collect/" + invoice,
        r,
        () -> {
          var i = lockInvoice(invoice);
          if (!db.queryForList(
                  "SELECT id FROM payment_provider_orders WHERE invoice_id=? AND status IN ('READY','CONFIRMING','DONE','REFUNDING')",
                  invoice)
              .isEmpty()) throw conflict("Toss 결제 주문이 있어 현장 수납으로 처리할 수 없습니다.");
          if (!"OPEN".equals(i.get("status"))) throw conflict("취소된 명세는 수납할 수 없습니다.");
          if (!i.get("source_hash").equals(quote(id(i, "work_order_id")).get("fingerprint")))
            throw conflict("부품 반환으로 내역이 변경되었습니다. 명세를 취소하고 다시 발행해 주세요.");
          var total = number(i, "total");
          if (total.compareTo(r.expectedTotal()) != 0) throw conflict("확인한 금액과 명세 금액이 다릅니다.");
          if (total.signum() == 0) throw conflict("0원 명세에는 수납 기록이 필요하지 않습니다.");
          BigDecimal outstanding = total.subtract(paid(invoice));
          if (outstanding.signum() <= 0) throw conflict("이미 수납한 명세입니다.");
          if (outstanding.compareTo(total) != 0)
            throw conflict("현재 정산은 남은 금액의 전액 수납만 지원합니다.");
          UUID payment = UUID.randomUUID();
          UUID actor = actor(email, true);
          db.update(
              """
INSERT INTO payment_records (id,invoice_id,operation_id,kind,amount,method,reference,reason,actor_id,created_at)
VALUES (?,?,?,'PAYMENT',?,?,?,'현장 수납 확인',?,?)
""",
              payment,
              invoice,
              key,
              total,
              r.method().name(),
              r.reference() == null ? "" : r.reference().strip(),
              actor,
              now());
          treasury.changeOperating(
              total,
              "CUSTOMER_PAYMENT",
              "고객 수납",
              "PAYMENT_RECORD",
              payment,
              actor);
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
          if (!db.queryForList("SELECT id FROM payment_provider_orders WHERE payment_record_id=?", payment).isEmpty())
            throw conflict("Toss 결제는 결제사 환불 기능을 이용해 주세요.");
          var original =
              one("SELECT * FROM payment_records WHERE id=? AND kind='PAYMENT'", payment);
          UUID invoice = id(original, "invoice_id");
          lockInvoice(invoice);
          if (!db.queryForList(
                  "SELECT id FROM payment_records WHERE original_payment_id=?", payment)
              .isEmpty()) throw conflict("이미 취소한 수납입니다.");
          UUID reversal = UUID.randomUUID();
          UUID actor = actor(email, true);
          db.update(
              """
INSERT INTO payment_records (id,invoice_id,operation_id,kind,original_payment_id,amount,method,reference,reason,actor_id,created_at)
VALUES (?,?,?,'REVERSAL',?,?,?,?,?,?,?)
""",
              reversal,
              invoice,
              key,
              payment,
              original.get("amount"),
              original.get("method"),
              original.get("reference"),
              r.reason().strip(),
              actor,
              now());
          treasury.changeOperating(
              number(original, "amount").negate(),
              "PAYMENT_REFUND",
              "고객 수납 취소: " + r.reason().strip(),
              "PAYMENT_RECORD",
              reversal,
              actor);
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
          if (!db.queryForList(
                  "SELECT id FROM payment_provider_orders WHERE invoice_id=? AND status IN ('READY','CONFIRMING','REFUNDING')",
                  invoice)
              .isEmpty()) throw conflict("처리 중인 Toss 결제 주문이 있습니다.");
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
            "SELECT p.id,p.kind,p.original_payment_id,p.amount,p.method,p.reference,p.reason,p.created_at,"
                + "o.provider,o.status AS provider_status FROM payment_records p"
                + " LEFT JOIN payment_provider_orders o ON o.payment_record_id=p.id"
                + " WHERE p.invoice_id=? ORDER BY p.created_at,p.id",
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
              "SELECT name,labor_price,done,quantity FROM work_order_items WHERE work_order_id=? ORDER BY"
                  + " name",
              w.get("id")));
      w.put(
          "invoices",
          rows(
              "SELECT i.id,i.status,i.total,i.issued_at,"
                  + " COALESCE((SELECT SUM(CASE WHEN p.kind='PAYMENT' THEN p.amount ELSE -p.amount END)"
                  + " FROM payment_records p WHERE p.invoice_id=i.id),0) AS paid,"
                  + " i.total-COALESCE((SELECT SUM(CASE WHEN p.kind='PAYMENT' THEN p.amount ELSE -p.amount END)"
                  + " FROM payment_records p WHERE p.invoice_id=i.id),0) AS balance"
                  + " FROM invoices i WHERE i.work_order_id=? ORDER BY i.issued_at DESC,i.id",
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
