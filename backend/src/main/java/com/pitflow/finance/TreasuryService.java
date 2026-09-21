package com.pitflow.finance;

import com.pitflow.common.ApiException;
import com.pitflow.finance.TreasuryRequests.PayrollPayment;
import com.pitflow.work.WorkService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TreasuryService {
  private static final List<String> ACCOUNT_ORDER =
      List.of("DEPOSIT", "INVESTMENT", "OPERATING");
  private final JdbcTemplate db;
  private final WorkService work;
  private final Clock clock;
  private final TreasuryMutationService mutations;

  public TreasuryService(
      JdbcTemplate db, WorkService work, Clock clock, TreasuryMutationService mutations) {
    this.db = db;
    this.work = work;
    this.clock = clock;
    this.mutations = mutations;
  }

  private UUID admin(String email) {
    var users = db.queryForList("SELECT id,role FROM users WHERE email=?", email);
    if (users.isEmpty()) throw new ApiException(HttpStatus.NOT_FOUND, "정보를 찾을 수 없습니다.");
    if (!"ADMIN".equals(users.get(0).get("role")))
      throw new ApiException(HttpStatus.FORBIDDEN, "관리자만 이용할 수 있습니다.");
    return UUID.fromString(users.get(0).get("id").toString());
  }

  private BigDecimal number(Object value) {
    return new BigDecimal(value.toString());
  }

  private List<Map<String, Object>> accountRows(boolean lock) {
    String sql =
        "SELECT account_type,balance,target_ratio,created_at,updated_at"
            + " FROM treasury_accounts ORDER BY account_type"
            + (lock ? " FOR UPDATE" : "");
    return db.queryForList(sql).stream()
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

  private Map<String, BigDecimal> targets(
      List<Map<String, Object>> accounts, BigDecimal total) {
    if (accounts.size() != 3)
      throw new IllegalStateException("Treasury accounts are incomplete");
    Map<String, Map<String, Object>> byType = new LinkedHashMap<>();
    BigDecimal ratioTotal = BigDecimal.ZERO;
    for (var account : accounts) {
      String type = account.get("account_type").toString();
      byType.put(type, account);
      ratioTotal = ratioTotal.add(number(account.get("target_ratio")));
    }
    if (!byType.keySet().containsAll(ACCOUNT_ORDER)
        || ratioTotal.compareTo(BigDecimal.ONE) != 0)
      throw new IllegalStateException("Treasury target ratios must total 1.00");
    BigDecimal operating =
        total
            .multiply(number(byType.get("OPERATING").get("target_ratio")))
            .setScale(0, RoundingMode.HALF_UP);
    BigDecimal deposit =
        total
            .multiply(number(byType.get("DEPOSIT").get("target_ratio")))
            .setScale(0, RoundingMode.HALF_UP);
    BigDecimal investment = total.subtract(operating).subtract(deposit);
    return Map.of(
        "OPERATING", operating,
        "DEPOSIT", deposit,
        "INVESTMENT", investment);
  }

  private Map<String, Object> response(List<Map<String, Object>> accounts) {
    BigDecimal total =
        accounts.stream()
            .map(account -> number(account.get("balance")))
            .reduce(BigDecimal.ZERO, BigDecimal::add);
    Map<String, BigDecimal> targets = targets(accounts, total);
    Map<String, Object> values = new LinkedHashMap<>();
    Object lastUpdated = null;
    boolean canRebalance = false;
    for (var account : accounts) {
      String type = account.get("account_type").toString();
      BigDecimal balance = number(account.get("balance"));
      BigDecimal targetRatio = number(account.get("target_ratio"));
      Map<String, Object> value = new LinkedHashMap<>();
      value.put("balance", balance);
      value.put("target_ratio", targetRatio.multiply(new BigDecimal("100")));
      value.put(
          "current_ratio",
          total.signum() == 0
              ? BigDecimal.ZERO
              : balance
                  .multiply(new BigDecimal("100"))
                  .divide(total, 2, RoundingMode.HALF_UP));
      values.put(type, value);
      canRebalance |= balance.compareTo(targets.get(type)) != 0;
      Object updated = account.get("updated_at");
      if (lastUpdated == null || updated.toString().compareTo(lastUpdated.toString()) > 0)
        lastUpdated = updated;
    }
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("total_assets", total);
    result.put("financial_assets_total", total);
    result.put("accounts", values);
    result.put("last_updated_at", lastUpdated);
    result.put("can_rebalance", canRebalance);
    Map<String, Object> inventory = inventoryAssets();
    BigDecimal receivables = receivables();
    result.put("inventory", inventory);
    result.put("receivables", receivables);
    result.put(
        "managed_assets_known_total",
        total.add(number(inventory.get("known_value"))).add(receivables));
    result.put(
        "managed_assets_fully_known",
        number(inventory.get("unknown_quantity")).signum() == 0);
    result.put("simulation", simulation());
    return result;
  }

  private Map<String, Object> inventoryAssets() {
    List<Map<String, Object>> items = new java.util.ArrayList<>();
    BigDecimal knownRaw = BigDecimal.ZERO;
    BigDecimal unknownQuantity = BigDecimal.ZERO;
    int unknownPartCount = 0;
    for (var row :
        db.queryForList(
            """
            SELECT p.id AS part_id,p.sku,p.name,p.unit,p.quantity AS on_hand_quantity,
              COALESCE(SUM(CASE WHEN l.cost_known=TRUE THEN l.remaining_quantity ELSE 0 END),0)
                AS known_quantity,
              COALESCE(SUM(CASE WHEN l.cost_known=TRUE
                THEN l.remaining_quantity*l.purchase_unit_cost ELSE 0 END),0) AS known_value_raw,
              COALESCE(SUM(CASE WHEN l.cost_known=FALSE THEN l.remaining_quantity ELSE 0 END),0)
                AS unknown_quantity
            FROM parts p
            LEFT JOIN inventory_cost_lots l ON l.part_id=p.id AND l.remaining_quantity>0
            GROUP BY p.id,p.sku,p.name,p.unit,p.quantity
            HAVING p.quantity>0 OR COALESCE(SUM(l.remaining_quantity),0)>0
            ORDER BY p.sku,p.id
            """)) {
      BigDecimal partKnownRaw = number(row.get("known_value_raw"));
      BigDecimal partUnknown = number(row.get("unknown_quantity"));
      knownRaw = knownRaw.add(partKnownRaw);
      unknownQuantity = unknownQuantity.add(partUnknown);
      if (partUnknown.signum() > 0) unknownPartCount++;
      Map<String, Object> item = new LinkedHashMap<>();
      item.put("part_id", row.get("part_id"));
      item.put("sku", row.get("sku"));
      item.put("name", row.get("name"));
      item.put("unit", row.get("unit"));
      item.put("on_hand_quantity", number(row.get("on_hand_quantity")));
      item.put("known_quantity", number(row.get("known_quantity")));
      item.put("known_asset_value", partKnownRaw.setScale(0, RoundingMode.HALF_UP));
      item.put("unknown_quantity", partUnknown);
      item.put("has_unknown_cost", partUnknown.signum() > 0);
      items.add(item);
    }
    Map<String, Object> inventory = new LinkedHashMap<>();
    inventory.put("known_value", knownRaw.setScale(0, RoundingMode.HALF_UP));
    inventory.put("unknown_quantity", unknownQuantity);
    inventory.put("unknown_part_count", unknownPartCount);
    inventory.put("items", items);
    return inventory;
  }

  private BigDecimal receivables() {
    return db.queryForObject(
        "SELECT COALESCE(SUM(i.total-COALESCE((SELECT SUM(CASE WHEN p.kind='PAYMENT'"
            + " THEN p.amount ELSE -p.amount END) FROM payment_records p"
            + " WHERE p.invoice_id=i.id),0)),0) FROM invoices i WHERE i.status='OPEN'",
        BigDecimal.class);
  }

  private Map<String, Object> simulation() {
    Map<String, Object> value = new LinkedHashMap<>();
    value.put("annual_deposit_rate", new BigDecimal("5.00"));
    var rows =
        db.queryForList(
            "SELECT * FROM treasury_daily_settlements ORDER BY settlement_date DESC FETCH FIRST 1 ROW ONLY");
    if (rows.isEmpty()) {
      value.put("last_settlement_date", null);
      value.put("last_deposit_interest", null);
      value.put("last_investment_return_rate", null);
      value.put("last_investment_return_amount", null);
    } else {
      var latest = rows.get(0);
      value.put("last_settlement_date", latest.get("settlement_date").toString());
      value.put("last_deposit_interest", number(latest.get("deposit_interest")));
      value.put(
          "last_investment_return_rate",
          number(latest.get("investment_return_rate")).multiply(new BigDecimal("100")));
      value.put(
          "last_investment_return_amount", number(latest.get("investment_return_amount")));
    }
    return value;
  }

  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public Map<String, Object> treasury(String email) {
    admin(email);
    return response(accountRows(false));
  }

  public Map<String, Object> rebalance(String email, UUID key) {
    UUID actor = admin(email);
    return work.billingCommand(
        email,
        key,
        "treasury-rebalance",
        Map.of(),
        () -> {
          List<Map<String, Object>> accounts = accountRows(true);
          BigDecimal total =
              accounts.stream()
                  .map(account -> number(account.get("balance")))
                  .reduce(BigDecimal.ZERO, BigDecimal::add);
          Map<String, BigDecimal> targets = targets(accounts, total);
          boolean changed =
              accounts.stream()
                  .anyMatch(
                      account ->
                          number(account.get("balance"))
                                  .compareTo(targets.get(account.get("account_type").toString()))
                              != 0);
          if (!changed) return response(accounts);

          UUID eventGroup = UUID.randomUUID();
          OffsetDateTime now = clock.instant().atOffset(ZoneOffset.UTC);
          for (var account : accounts) {
            String type = account.get("account_type").toString();
            BigDecimal before = number(account.get("balance"));
            BigDecimal after = targets.get(type);
            db.update(
                "UPDATE treasury_accounts SET balance=?,updated_at=? WHERE account_type=?",
                after,
                now,
                type);
            db.update(
                "INSERT INTO treasury_ledger"
                    + " (id,event_group_id,account_type,event_type,amount_delta,balance_after,"
                    + "reason,created_by,created_at) VALUES (?,?,?,'REBALANCE',?,?,?,?,?)",
                UUID.randomUUID(),
                eventGroup,
                type,
                after.subtract(before),
                after,
                "목표 비중으로 재조정",
                actor,
                now);
          }
          return response(accountRows(false));
        });
  }

  public Map<String, Object> payrollPayment(
      String email, UUID key, PayrollPayment request) {
    UUID actor = admin(email);
    PayrollPayment normalized =
        new PayrollPayment(request.amount(), request.paymentDate(), request.reason().strip());
    return work.billingCommand(
        email,
        key,
        "treasury-payroll-payment",
        normalized,
        () -> {
          mutations.changeOperating(
              normalized.amount().negate(),
              "PAYROLL_PAYMENT",
              normalized.paymentDate() + " 급여 실제 지급: " + normalized.reason(),
              "PAYROLL_PAYMENT",
              key,
              actor);
          return response(accountRows(false));
        });
  }
}
