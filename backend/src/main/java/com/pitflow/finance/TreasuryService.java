package com.pitflow.finance;

import com.pitflow.common.ApiException;
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

  public TreasuryService(JdbcTemplate db, WorkService work, Clock clock) {
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
    result.put("accounts", values);
    result.put("last_updated_at", lastUpdated);
    result.put("can_rebalance", canRebalance);
    return result;
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
}
