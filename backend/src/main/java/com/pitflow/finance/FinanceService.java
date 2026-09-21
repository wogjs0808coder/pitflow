package com.pitflow.finance;

import com.pitflow.common.ApiException;
import com.pitflow.finance.FinanceRequests.CostResolution;
import com.pitflow.finance.FinanceRequests.Entry;
import com.pitflow.finance.FinanceRequests.Reversal;
import com.pitflow.finance.FinanceRequests.Settings;
import com.pitflow.work.WorkService;
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
  private final WorkService work;
  private final Clock clock;

  public FinanceService(JdbcTemplate db, WorkService work, Clock clock) {
    this.db = db;
    this.work = work;
    this.clock = clock;
  }

  private UUID admin(String email) {
    var users = db.queryForList("SELECT id,role FROM users WHERE email=?", email);
    if (users.isEmpty()) throw new ApiException(HttpStatus.NOT_FOUND, "정보를 찾을 수 없습니다.");
    if (!"ADMIN".equals(users.get(0).get("role")))
      throw new ApiException(HttpStatus.FORBIDDEN, "관리자만 이용할 수 있습니다.");
    return UUID.fromString(users.get(0).get("id").toString());
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

  private LocalDate[] effectivePeriod(LocalDate from, LocalDate to) {
    LocalDate today = LocalDate.now(clock.withZone(SEOUL));
    LocalDate effectiveFrom = from == null ? today.withDayOfMonth(1) : from;
    LocalDate effectiveTo = to == null ? today : to;
    period(effectiveFrom, effectiveTo);
    return new LocalDate[] {effectiveFrom, effectiveTo};
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

    Set<UUID> workIds = new HashSet<>();
    works.forEach(work -> workIds.add(UUID.fromString(work.get("id").toString())));

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

    Map<UUID, ManualResolution> resolutions = resolutions(workIds);

    for (var work : works) {
      UUID id = UUID.fromString(work.get("id").toString());
      var part = parts.getOrDefault(id, Map.of());
      BigDecimal automaticPartsCost =
          number(part.get("known_cost")).setScale(0, RoundingMode.HALF_UP);
      BigDecimal unknownQuantity = number(part.get("unknown_quantity"));
      boolean automaticUnknownParts = unknownQuantity.signum() > 0;
      boolean automaticLaborKnown = Boolean.TRUE.equals(work.get("labor_cost_known"));
      BigDecimal automaticLaborCost =
          automaticLaborKnown ? number(work.get("labor_cost_snapshot")) : null;
      ManualResolution resolution = resolutions.getOrDefault(id, new ManualResolution());
      boolean manualPartsUsed = automaticUnknownParts && resolution.partsFound;
      boolean manualLaborUsed = !automaticLaborKnown && resolution.laborFound;
      BigDecimal effectivePartsCost =
          automaticPartsCost.add(manualPartsUsed ? resolution.parts : BigDecimal.ZERO);
      BigDecimal effectiveLaborCost =
          automaticLaborKnown
              ? automaticLaborCost
              : manualLaborUsed ? resolution.labor : null;
      boolean unknownParts = automaticUnknownParts && !manualPartsUsed;
      boolean unknownLabor = effectiveLaborCost == null;
      BigDecimal revenue = number(work.get("revenue"));
      BigDecimal paid = number(work.get("paid_amount"));
      boolean allKnown = !unknownLabor && !unknownParts;
      work.put("invoice_issued", work.get("invoice_id") != null);
      work.put("revenue", revenue);
      work.put("paid_amount", paid);
      work.put("outstanding_amount", revenue.subtract(paid));
      work.put("automatic_parts_cost_known", automaticPartsCost);
      work.put("manual_unresolved_parts_cost", manualPartsUsed ? resolution.parts : null);
      work.put("parts_cost_known", automaticPartsCost);
      work.put("parts_cost", effectivePartsCost);
      work.put("parts_cost_manually_resolved", manualPartsUsed);
      work.put("unknown_parts_quantity", unknownQuantity);
      work.put("has_unknown_parts_cost", unknownParts);
      work.put("automatic_labor_cost", automaticLaborCost);
      work.put("manual_labor_cost", manualLaborUsed ? resolution.labor : null);
      work.put("labor_cost", effectiveLaborCost);
      work.put("labor_cost_manually_resolved", manualLaborUsed);
      work.put("has_unknown_labor_cost", unknownLabor);
      work.put("has_unknown_cost", !allKnown);
      work.put("total_cost", allKnown ? effectivePartsCost.add(effectiveLaborCost) : null);
      work.put(
          "contribution_margin",
          allKnown ? revenue.subtract(effectivePartsCost).subtract(effectiveLaborCost) : null);
      work.put("cost_resolution_reason", resolution.reason);
      work.put("cost_resolution_at", resolution.at);
      work.put("cost_resolution_by", resolution.by);
      BigDecimal currentSalary =
          work.get("current_monthly_base_salary") == null
              ? null
              : number(work.get("current_monthly_base_salary"));
      BigDecimal currentHours = number(work.get("current_monthly_standard_hours"));
      work.put(
          "current_derived_hourly_cost",
          currentSalary == null
              ? null
              : currentSalary.divide(currentHours, 0, RoundingMode.HALF_UP));
    }
    return works;
  }

  private Map<UUID, ManualResolution> resolutions(Set<UUID> workIds) {
    if (workIds.isEmpty()) return Map.of();
    String placeholders = String.join(",", Collections.nCopies(workIds.size(), "?"));
    Map<UUID, ManualResolution> result = new HashMap<>();
    for (var row :
        rows(
            "SELECT r.*,u.name AS resolved_by_name FROM work_order_cost_resolutions r"
                + " JOIN users u ON u.id=r.resolved_by WHERE r.work_order_id IN ("
                + placeholders
                + ") ORDER BY r.work_order_id,r.created_at DESC,r.id DESC",
            workIds.toArray())) {
      UUID workId = UUID.fromString(row.get("work_order_id").toString());
      ManualResolution value = result.computeIfAbsent(workId, ignored -> new ManualResolution());
      if (value.reason == null) {
        value.reason = row.get("reason").toString();
        value.at = row.get("created_at");
        value.by = row.get("resolved_by_name");
      }
      if (!value.partsFound && row.get("unresolved_parts_cost") != null) {
        value.parts = number(row.get("unresolved_parts_cost"));
        value.partsFound = true;
      }
      if (!value.laborFound && row.get("labor_cost") != null) {
        value.labor = number(row.get("labor_cost"));
        value.laborFound = true;
      }
    }
    return result;
  }

  private static final class ManualResolution {
    private BigDecimal parts;
    private BigDecimal labor;
    private boolean partsFound;
    private boolean laborFound;
    private Object reason;
    private Object at;
    private Object by;
  }

  private static final String BASE_SQL =
      """
      SELECT w.id,w.vehicle_label,w.plate_number,w.mechanic_id,w.mechanic_name,w.completed_at,
        w.labor_minutes_snapshot,w.labor_hourly_cost_snapshot,w.labor_cost_snapshot,
        m.monthly_base_salary AS current_monthly_base_salary,
        m.monthly_standard_hours AS current_monthly_standard_hours,
        w.labor_cost_known,i.id AS invoice_id,i.total AS revenue,
        COALESCE((SELECT SUM(CASE WHEN p.kind='PAYMENT' THEN p.amount ELSE -p.amount END)
          FROM payment_records p WHERE p.invoice_id=i.id),0) AS paid_amount
      FROM work_orders w
      LEFT JOIN mechanics m ON m.id=w.mechanic_id
      LEFT JOIN invoices i ON i.active_work_order_id=w.id AND i.status='OPEN'
      """;

  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public List<Map<String, Object>> list(String email, LocalDate from, LocalDate to) {
    admin(email);
    LocalDate[] dates = effectivePeriod(from, to);
    return report(dates[0], dates[1], null);
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
    LocalDate[] dates = effectivePeriod(from, to);
    from = dates[0];
    to = dates[1];
    var values = report(from, to, null);
    BigDecimal revenue = BigDecimal.ZERO;
    BigDecimal paid = BigDecimal.ZERO;
    BigDecimal parts = BigDecimal.ZERO;
    BigDecimal laborKnown = BigDecimal.ZERO;
    boolean unknown = false;
    boolean unknownParts = false;
    boolean unknownLabor = false;
    int invoiced = 0;
    for (var value : values) {
      revenue = revenue.add(number(value.get("revenue")));
      paid = paid.add(number(value.get("paid_amount")));
      parts = parts.add(number(value.get("parts_cost")));
      if (value.get("labor_cost") != null) laborKnown = laborKnown.add(number(value.get("labor_cost")));
      unknown |= Boolean.TRUE.equals(value.get("has_unknown_cost"));
      unknownParts |= Boolean.TRUE.equals(value.get("has_unknown_parts_cost"));
      unknownLabor |= Boolean.TRUE.equals(value.get("has_unknown_labor_cost"));
      if (Boolean.TRUE.equals(value.get("invoice_issued"))) invoiced++;
    }
    Payroll payroll = payroll(from, to, values);
    Map<String, BigDecimal> entries = entryTotals(from, to);
    BigDecimal operatingExpenses = BigDecimal.ZERO;
    for (String category : OPERATING_CATEGORIES)
      operatingExpenses = operatingExpenses.add(entries.getOrDefault(category, BigDecimal.ZERO));
    BigDecimal otherIncome = entries.getOrDefault("OTHER_INCOME", BigDecimal.ZERO);
    BigDecimal interest = entries.getOrDefault("INTEREST", BigDecimal.ZERO);
    BigDecimal tax = entries.getOrDefault("TAX", BigDecimal.ZERO);
    BigDecimal contribution = unknown ? null : revenue.subtract(parts).subtract(laborKnown);
    BigDecimal gross =
        unknownParts || !payroll.known ? null : revenue.subtract(parts).subtract(payroll.total);
    BigDecimal operatingProfit = gross == null ? null : gross.subtract(operatingExpenses);
    BigDecimal preTax =
        operatingProfit == null ? null : operatingProfit.add(otherIncome).subtract(interest);
    BigDecimal net = preTax == null ? null : preTax.subtract(tax);
    BigDecimal allocationVariance =
        !payroll.known || unknownLabor ? null : payroll.total.subtract(laborKnown);
    BigDecimal completedMinutes = BigDecimal.ZERO;
    boolean completedMinutesKnown = true;
    for (var value : values) {
      if (value.get("labor_minutes_snapshot") == null) completedMinutesKnown = false;
      else completedMinutes = completedMinutes.add(number(value.get("labor_minutes_snapshot")));
    }
    BigDecimal completedHours =
        completedMinutesKnown
            ? completedMinutes.divide(new BigDecimal("60"), 2, RoundingMode.HALF_UP)
            : null;
    BigDecimal availableHours = payroll.availableHours.setScale(2, RoundingMode.HALF_UP);
    Map<String, Object> settings = settingsRow();
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("from", from);
    result.put("to", to);
    result.put("work_order_count", values.size());
    result.put("invoiced_work_order_count", invoiced);
    result.put("revenue", revenue);
    result.put("paid_amount", paid);
    result.put("outstanding_amount", revenue.subtract(paid));
    result.put("parts_cost_known", parts);
    result.put("labor_cost_known", laborKnown);
    result.put("parts_cost", parts);
    result.put("labor_cost", laborKnown);
    result.put("has_unknown_cost", unknown);
    result.put("total_cost", unknown ? null : parts.add(laborKnown));
    result.put("contribution_margin", contribution);
    result.put("collection_rate", ratio(paid, revenue));
    result.put(
        "average_repair_order",
        invoiced == 0
            ? null
            : revenue.divide(BigDecimal.valueOf(invoiced), 0, RoundingMode.HALF_UP));
    result.put("parts_cost_ratio", unknownParts ? null : ratio(parts, revenue));
    result.put("allocated_labor_cost", laborKnown);
    result.put("period_payroll_expense", payroll.known ? payroll.total : null);
    result.put("payroll_expense", payroll.known ? payroll.total : null);
    result.put("payroll_unknown", !payroll.known);
    result.put("payroll_ratio", payroll.known ? ratio(payroll.total, revenue) : null);
    result.put("target_payroll_ratio", settings.get("target_payroll_ratio"));
    result.put("labor_allocation_variance", allocationVariance);
    result.put("contribution_margin_ratio", contribution == null ? null : ratio(contribution, revenue));
    result.put("gross_profit", gross);
    result.put("operating_expenses", operatingExpenses);
    result.put("operating_profit", operatingProfit);
    result.put("operating_margin", operatingProfit == null ? null : ratio(operatingProfit, revenue));
    result.put("other_income", otherIncome);
    result.put("interest_expense", interest);
    result.put("pre_tax_profit", preTax);
    result.put("tax_expense", tax);
    result.put("net_profit", net);
    result.put("expense_by_category", entries);
    result.put("mechanics", payroll.mechanics);
    result.put("completed_labor_hours", completedHours);
    result.put("standard_available_hours", availableHours);
    result.put("labor_utilization_rate", ratio(completedHours, availableHours));
    result.put(
        "revenue_per_mechanic",
        payroll.mechanics.isEmpty()
            ? null
            : revenue.divide(
                BigDecimal.valueOf(payroll.mechanics.size()), 0, RoundingMode.HALF_UP));
    return result;
  }

  private static final Set<String> OPERATING_CATEGORIES =
      Set.of(
          "RENT",
          "UTILITIES",
          "INSURANCE",
          "SOFTWARE",
          "SHOP_SUPPLIES",
          "EQUIPMENT_MAINTENANCE",
          "CARD_FEES",
          "DEPRECIATION",
          "OTHER_OPERATING");

  private BigDecimal ratio(BigDecimal numerator, BigDecimal denominator) {
    if (numerator == null || denominator == null || denominator.signum() == 0) return null;
    return numerator
        .multiply(new BigDecimal("100"))
        .divide(denominator, 2, RoundingMode.HALF_UP);
  }

  private BigDecimal prorate(
      BigDecimal monthlyValue, LocalDate from, LocalDate to, int scale) {
    BigDecimal total = BigDecimal.ZERO;
    YearMonth month = YearMonth.from(from);
    YearMonth last = YearMonth.from(to);
    while (!month.isAfter(last)) {
      LocalDate start = from.isAfter(month.atDay(1)) ? from : month.atDay(1);
      LocalDate end = to.isBefore(month.atEndOfMonth()) ? to : month.atEndOfMonth();
      long days = ChronoUnit.DAYS.between(start, end) + 1;
      total =
          total.add(
              monthlyValue
                  .multiply(BigDecimal.valueOf(days))
                  .divide(BigDecimal.valueOf(month.lengthOfMonth()), scale, RoundingMode.HALF_UP));
      month = month.plusMonths(1);
    }
    return total;
  }

  private Payroll payroll(
      LocalDate from, LocalDate to, List<Map<String, Object>> workOrders) {
    Map<UUID, BigDecimal> allocatedCost = new HashMap<>();
    Map<UUID, BigDecimal> allocatedMinutes = new HashMap<>();
    Set<UUID> unknownAllocatedMinutes = new HashSet<>();
    for (var order : workOrders) {
      if (order.get("mechanic_id") == null) continue;
      UUID mechanic = UUID.fromString(order.get("mechanic_id").toString());
      if (order.get("labor_minutes_snapshot") == null) unknownAllocatedMinutes.add(mechanic);
      else
        allocatedMinutes.merge(
            mechanic, number(order.get("labor_minutes_snapshot")), BigDecimal::add);
      if (order.get("labor_cost") != null)
        allocatedCost.merge(mechanic, number(order.get("labor_cost")), BigDecimal::add);
    }
    boolean known = true;
    BigDecimal total = BigDecimal.ZERO;
    BigDecimal availableHours = BigDecimal.ZERO;
    List<Map<String, Object>> mechanics = new ArrayList<>();
    for (var mechanic :
        rows(
            "SELECT id,code,name,monthly_base_salary,monthly_standard_hours,hourly_cost"
                + " FROM mechanics WHERE active=TRUE ORDER BY code")) {
      UUID id = UUID.fromString(mechanic.get("id").toString());
      BigDecimal salary =
          mechanic.get("monthly_base_salary") == null
              ? null
              : number(mechanic.get("monthly_base_salary"));
      BigDecimal standardHours = number(mechanic.get("monthly_standard_hours"));
      BigDecimal payrollExpense = salary == null ? null : prorate(salary, from, to, 0);
      BigDecimal mechanicAvailable = prorate(standardHours, from, to, 4);
      availableHours = availableHours.add(mechanicAvailable);
      if (payrollExpense == null) known = false;
      else total = total.add(payrollExpense);
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("id", id);
      row.put("code", mechanic.get("code"));
      row.put("name", mechanic.get("name"));
      row.put("monthly_base_salary", salary);
      row.put("monthly_standard_hours", standardHours);
      row.put("derived_hourly_cost", mechanic.get("hourly_cost"));
      row.put(
          "allocated_minutes",
          unknownAllocatedMinutes.contains(id)
              ? null
              : allocatedMinutes.getOrDefault(id, BigDecimal.ZERO));
      row.put("allocated_labor_cost", allocatedCost.getOrDefault(id, BigDecimal.ZERO));
      row.put("period_payroll_expense", payrollExpense);
      row.put("standard_available_hours", mechanicAvailable.setScale(2, RoundingMode.HALF_UP));
      mechanics.add(row);
    }
    return new Payroll(known, total, availableHours, mechanics);
  }

  private Map<String, BigDecimal> entryTotals(LocalDate from, LocalDate to) {
    Map<String, BigDecimal> totals = new LinkedHashMap<>();
    for (var row :
        rows(
            "SELECT category,COALESCE(SUM(CASE WHEN entry_kind='ENTRY' THEN amount"
                + " ELSE -amount END),0) AS total FROM finance_entries"
                + " WHERE entry_date>=? AND entry_date<=? GROUP BY category ORDER BY category",
            from,
            to)) totals.put(row.get("category").toString(), number(row.get("total")));
    return totals;
  }

  private Map<String, Object> settingsRow() {
    return rows("SELECT * FROM finance_settings WHERE id=1").get(0);
  }

  private record Payroll(
      boolean known,
      BigDecimal total,
      BigDecimal availableHours,
      List<Map<String, Object>> mechanics) {}

  public Map<String, Object> resolveCost(
      String email, UUID key, UUID workOrder, CostResolution request) {
    UUID resolvedBy = admin(email);
    if (request.unresolvedPartsCost() == null && request.laborCost() == null)
      throw new ApiException(HttpStatus.BAD_REQUEST, "확정할 원가를 하나 이상 입력해 주세요.");
    String reason = request.reason().strip();
    CostResolution normalized =
        new CostResolution(request.unresolvedPartsCost(), request.laborCost(), reason);
    return work.billingCommand(
        email,
        key,
        "finance-cost-resolution/" + workOrder,
        normalized,
        () -> {
          var orders =
              db.queryForList(
                  "SELECT status,labor_cost_known FROM work_orders WHERE id=? FOR UPDATE",
                  workOrder);
          if (orders.isEmpty())
            throw new ApiException(HttpStatus.NOT_FOUND, "완료된 작업을 찾을 수 없습니다.");
          var order = orders.get(0);
          if (!"COMPLETED".equals(order.get("status")))
            throw new ApiException(HttpStatus.CONFLICT, "완료된 작업의 원가만 확정할 수 있습니다.");
          BigDecimal unknownParts =
              db.queryForObject(
                  "SELECT COALESCE(SUM(CASE WHEN a.cost_known=FALSE THEN"
                      + " CASE WHEN a.allocation_type='CONSUME' THEN a.quantity ELSE -a.quantity END"
                      + " ELSE 0 END),0) FROM inventory_cost_allocations a"
                      + " JOIN stock_movements m ON m.id=a.movement_id WHERE m.work_order_id=?",
                  BigDecimal.class,
                  workOrder);
          if (request.unresolvedPartsCost() != null && unknownParts.signum() <= 0)
            throw new ApiException(HttpStatus.CONFLICT, "미확정 부품 원가가 없습니다.");
          if (request.laborCost() != null && Boolean.TRUE.equals(order.get("labor_cost_known")))
            throw new ApiException(HttpStatus.CONFLICT, "자동 확정된 인건비 원가는 수동으로 변경할 수 없습니다.");
          OffsetDateTime createdAt = clock.instant().atOffset(ZoneOffset.UTC);
          Object latest =
              db.queryForObject(
                  "SELECT MAX(created_at) FROM work_order_cost_resolutions WHERE work_order_id=?",
                  Object.class,
                  workOrder);
          if (latest != null) {
            OffsetDateTime latestTime =
                latest instanceof OffsetDateTime time
                    ? time
                    : latest instanceof Timestamp timestamp
                        ? timestamp.toInstant().atOffset(ZoneOffset.UTC)
                        : OffsetDateTime.parse(latest.toString());
            if (!createdAt.isAfter(latestTime)) createdAt = latestTime.plusNanos(1_000);
          }
          db.update(
              "INSERT INTO work_order_cost_resolutions"
                  + " (id,work_order_id,unresolved_parts_cost,labor_cost,reason,resolved_by,created_at)"
                  + " VALUES (?,?,?,?,?,?,?)",
              UUID.randomUUID(),
              workOrder,
              request.unresolvedPartsCost(),
              request.laborCost(),
              reason,
              resolvedBy,
              createdAt);
          return detail(email, workOrder);
        });
  }

  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public Map<String, Object> settings(String email) {
    admin(email);
    return settingsRow();
  }

  public Map<String, Object> updateSettings(String email, UUID key, Settings request) {
    UUID actor = admin(email);
    return work.billingCommand(
        email,
        key,
        "finance-settings",
        request,
        () -> {
          db.update(
              "UPDATE finance_settings SET default_monthly_base_salary=?,"
                  + "default_monthly_standard_hours=?,target_payroll_ratio=?,updated_at=?,"
                  + "updated_by=? WHERE id=1",
              request.defaultMonthlyBaseSalary(),
              request.defaultMonthlyStandardHours(),
              request.targetPayrollRatio(),
              clock.instant().atOffset(ZoneOffset.UTC),
              actor);
          return settingsRow();
        });
  }

  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public List<Map<String, Object>> entries(
      String email, LocalDate from, LocalDate to) {
    admin(email);
    LocalDate[] dates = effectivePeriod(from, to);
    return rows(
        "SELECT e.*,u.name AS created_by_name,"
            + "EXISTS(SELECT 1 FROM finance_entries r WHERE r.original_entry_id=e.id) AS reversed"
            + " FROM finance_entries e JOIN users u ON u.id=e.created_by"
            + " WHERE e.entry_date>=? AND e.entry_date<=?"
            + " ORDER BY e.entry_date DESC,e.created_at DESC,e.id DESC",
        dates[0],
        dates[1]);
  }

  public Map<String, Object> createEntry(
      String email, UUID key, Entry request) {
    UUID actor = admin(email);
    period(request.entryDate(), request.entryDate());
    Entry normalized =
        new Entry(request.entryDate(), request.category(), request.amount(), request.description().strip());
    return work.billingCommand(
        email,
        key,
        "finance-entry",
        normalized,
        () -> {
          UUID id = UUID.randomUUID();
          db.update(
              "INSERT INTO finance_entries"
                  + " (id,entry_date,category,amount,description,entry_kind,original_entry_id,created_by,created_at)"
                  + " VALUES (?,?,?,?,?,'ENTRY',NULL,?,?)",
              id,
              normalized.entryDate(),
              normalized.category().name(),
              normalized.amount(),
              normalized.description(),
              actor,
              clock.instant().atOffset(ZoneOffset.UTC));
          return rows("SELECT * FROM finance_entries WHERE id=?", id).get(0);
        });
  }

  public Map<String, Object> reverseEntry(
      String email, UUID key, UUID entryId, Reversal request) {
    UUID actor = admin(email);
    Reversal normalized = new Reversal(request.reason().strip());
    return work.billingCommand(
        email,
        key,
        "finance-entry-reversal/" + entryId,
        normalized,
        () -> {
          var found =
              db.queryForList(
                  "SELECT * FROM finance_entries WHERE id=? AND entry_kind='ENTRY' FOR UPDATE",
                  entryId);
          if (found.isEmpty())
            throw new ApiException(HttpStatus.NOT_FOUND, "비용 항목을 찾을 수 없습니다.");
          if (Boolean.TRUE.equals(
              db.queryForObject(
                  "SELECT COUNT(*)>0 FROM finance_entries WHERE original_entry_id=?",
                  Boolean.class,
                  entryId)))
            throw new ApiException(HttpStatus.CONFLICT, "이미 취소된 비용 항목입니다.");
          var original = found.get(0);
          UUID reversal = UUID.randomUUID();
          db.update(
              "INSERT INTO finance_entries"
                  + " (id,entry_date,category,amount,description,entry_kind,original_entry_id,created_by,created_at)"
                  + " VALUES (?,?,?,?,?,'REVERSAL',?,?,?)",
              reversal,
              original.get("entry_date"),
              original.get("category"),
              original.get("amount"),
              normalized.reason(),
              entryId,
              actor,
              clock.instant().atOffset(ZoneOffset.UTC));
          return rows("SELECT * FROM finance_entries WHERE id=?", reversal).get(0);
        });
  }
}
