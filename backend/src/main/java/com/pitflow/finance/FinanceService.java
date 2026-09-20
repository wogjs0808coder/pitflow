package com.pitflow.finance;

import com.pitflow.common.ApiException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class FinanceService {
  private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
  private final JdbcTemplate db;

  public FinanceService(JdbcTemplate db) {
    this.db = db;
  }

  private void admin(String email) {
    var roles = db.queryForList("SELECT role FROM users WHERE email=?", String.class, email);
    if (roles.isEmpty()) throw new ApiException(HttpStatus.NOT_FOUND, "정보를 찾을 수 없습니다.");
    if (!"ADMIN".equals(roles.get(0)))
      throw new ApiException(HttpStatus.FORBIDDEN, "관리자만 이용할 수 있습니다.");
  }

  private void period(LocalDate from, LocalDate to) {
    if (from == null
        || to == null
        || from.getYear() < 1900
        || to.getYear() > 2100
        || to.isBefore(from)
        || ChronoUnit.DAYS.between(from, to) > 366)
      throw new ApiException(HttpStatus.BAD_REQUEST, "조회 기간은 1년 이내로 입력해 주세요.");
  }

  private BigDecimal number(Object value) {
    return value == null ? BigDecimal.ZERO : new BigDecimal(value.toString());
  }

  private List<Map<String, Object>> rows(String sql, Object... args) {
    return db.queryForList(sql, args).stream()
        .map(
            row -> {
              Map<String, Object> clean = new LinkedHashMap<>();
              row.forEach(
                  (key, value) ->
                      clean.put(
                          key.toLowerCase(Locale.ROOT),
                          value instanceof Timestamp timestamp
                              ? timestamp.toInstant().toString()
                              : value instanceof OffsetDateTime time ? time.toString() : value));
              return clean;
            })
        .toList();
  }

  private List<Map<String, Object>> report(
      LocalDate from, LocalDate to, UUID onlyWorkOrder) {
    List<Map<String, Object>> works;
    if (onlyWorkOrder != null) {
      works =
          rows(
              BASE_SQL + " WHERE w.id=? AND w.status='COMPLETED'",
              onlyWorkOrder);
    } else {
      OffsetDateTime start = from.atStartOfDay(SEOUL).toOffsetDateTime();
      OffsetDateTime end = to.plusDays(1).atStartOfDay(SEOUL).toOffsetDateTime();
      works =
          rows(
              BASE_SQL
                  + " WHERE w.status='COMPLETED' AND w.completed_at>=? AND w.completed_at<?"
                  + " ORDER BY w.completed_at DESC,w.id",
              start,
              end);
    }
    if (works.isEmpty()) return works;

    Map<UUID, Map<String, Object>> parts = new HashMap<>();
    for (var row :
        rows(
            """
            SELECT m.work_order_id,
              COALESCE(SUM(CASE WHEN a.cost_known=TRUE THEN
                (CASE WHEN a.allocation_type='CONSUME' THEN a.quantity ELSE -a.quantity END)
                * a.purchase_unit_cost ELSE 0 END),0) AS known_cost,
              COALESCE(SUM(CASE WHEN a.cost_known=FALSE THEN
                CASE WHEN a.allocation_type='CONSUME' THEN a.quantity ELSE -a.quantity END
                ELSE 0 END),0) AS unknown_quantity
            FROM inventory_cost_allocations a
            JOIN stock_movements m ON m.id=a.movement_id
            WHERE m.work_order_id IS NOT NULL
            GROUP BY m.work_order_id
            """))
      parts.put(UUID.fromString(row.get("work_order_id").toString()), row);

    for (var work : works) {
      UUID id = UUID.fromString(work.get("id").toString());
      var part = parts.getOrDefault(id, Map.of());
      BigDecimal partsCost = number(part.get("known_cost")).setScale(0, RoundingMode.HALF_UP);
      BigDecimal unknownQuantity = number(part.get("unknown_quantity"));
      boolean unknownParts = unknownQuantity.signum() > 0;
      boolean laborKnown = Boolean.TRUE.equals(work.get("labor_cost_known"));
      BigDecimal laborCost = laborKnown ? number(work.get("labor_cost_snapshot")) : BigDecimal.ZERO;
      BigDecimal revenue = number(work.get("revenue"));
      BigDecimal paid = number(work.get("paid_amount"));
      boolean allKnown = laborKnown && !unknownParts;
      work.put("invoice_issued", work.get("invoice_id") != null);
      work.put("revenue", revenue);
      work.put("paid_amount", paid);
      work.put("outstanding_amount", revenue.subtract(paid));
      work.put("parts_cost_known", partsCost);
      work.put("unknown_parts_quantity", unknownQuantity);
      work.put("has_unknown_parts_cost", unknownParts);
      work.put("labor_cost", laborKnown ? laborCost : null);
      work.put("has_unknown_labor_cost", !laborKnown);
      work.put("has_unknown_cost", !allKnown);
      work.put("total_cost", allKnown ? partsCost.add(laborCost) : null);
      work.put(
          "contribution_margin",
          allKnown ? revenue.subtract(partsCost).subtract(laborCost) : null);
    }
    return works;
  }

  private static final String BASE_SQL =
      """
      SELECT w.id,w.vehicle_label,w.plate_number,w.mechanic_name,w.completed_at,
        w.labor_minutes_snapshot,w.labor_hourly_cost_snapshot,w.labor_cost_snapshot,
        w.labor_cost_known,i.id AS invoice_id,i.total AS revenue,
        COALESCE((SELECT SUM(CASE WHEN p.kind='PAYMENT' THEN p.amount ELSE -p.amount END)
          FROM payment_records p WHERE p.invoice_id=i.id),0) AS paid_amount
      FROM work_orders w
      LEFT JOIN invoices i ON i.active_work_order_id=w.id AND i.status='OPEN'
      """;

  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public List<Map<String, Object>> list(String email, LocalDate from, LocalDate to) {
    admin(email);
    period(from, to);
    return report(from, to, null);
  }

  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public Map<String, Object> detail(String email, UUID workOrder) {
    admin(email);
    var values = report(null, null, workOrder);
    if (values.isEmpty()) throw new ApiException(HttpStatus.NOT_FOUND, "완료된 작업을 찾을 수 없습니다.");
    return values.get(0);
  }

  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public Map<String, Object> summary(String email, LocalDate from, LocalDate to) {
    admin(email);
    period(from, to);
    var values = report(from, to, null);
    BigDecimal revenue = BigDecimal.ZERO;
    BigDecimal paid = BigDecimal.ZERO;
    BigDecimal parts = BigDecimal.ZERO;
    BigDecimal laborKnown = BigDecimal.ZERO;
    boolean unknown = false;
    for (var value : values) {
      revenue = revenue.add(number(value.get("revenue")));
      paid = paid.add(number(value.get("paid_amount")));
      parts = parts.add(number(value.get("parts_cost_known")));
      if (value.get("labor_cost") != null) laborKnown = laborKnown.add(number(value.get("labor_cost")));
      unknown |= Boolean.TRUE.equals(value.get("has_unknown_cost"));
    }
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("from", from);
    result.put("to", to);
    result.put("work_order_count", values.size());
    result.put("revenue", revenue);
    result.put("paid_amount", paid);
    result.put("outstanding_amount", revenue.subtract(paid));
    result.put("parts_cost_known", parts);
    result.put("labor_cost_known", laborKnown);
    result.put("has_unknown_cost", unknown);
    result.put("total_cost", unknown ? null : parts.add(laborKnown));
    result.put("contribution_margin", unknown ? null : revenue.subtract(parts).subtract(laborKnown));
    return result;
  }
}
