package com.pitflow.finance;

import com.pitflow.common.ApiException;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class TreasuryMutationService {
  private final JdbcTemplate db;
  private final Clock clock;

  public TreasuryMutationService(JdbcTemplate db, Clock clock) {
    this.db = db;
    this.clock = clock;
  }

  public Map<String, BigDecimal> lockBalances() {
    Map<String, BigDecimal> balances = new LinkedHashMap<>();
    db.queryForList(
            "SELECT account_type,balance FROM treasury_accounts ORDER BY account_type FOR UPDATE")
        .forEach(
            row ->
                balances.put(
                    row.get("account_type").toString(),
                    new BigDecimal(row.get("balance").toString())));
    if (!balances.keySet().equals(java.util.Set.of("DEPOSIT", "INVESTMENT", "OPERATING")))
      throw new IllegalStateException("Treasury accounts are incomplete");
    return balances;
  }

  public BigDecimal changeOperating(
      BigDecimal delta,
      String eventType,
      String reason,
      String sourceType,
      UUID sourceId,
      UUID actor) {
    Map<String, BigDecimal> balances = lockBalances();
    return changeLocked(
        balances,
        "OPERATING",
        delta,
        eventType,
        reason,
        sourceType,
        sourceId,
        actor,
        UUID.randomUUID(),
        false);
  }

  public BigDecimal changeLocked(
      Map<String, BigDecimal> balances,
      String accountType,
      BigDecimal delta,
      String eventType,
      String reason,
      String sourceType,
      UUID sourceId,
      UUID actor,
      UUID eventGroup,
      boolean recordZero) {
    BigDecimal before = balances.get(accountType);
    if (before == null) throw new IllegalStateException("Unknown Treasury account: " + accountType);
    BigDecimal normalized = delta.setScale(0);
    BigDecimal after = before.add(normalized);
    if (after.signum() < 0)
      throw new ApiException(HttpStatus.CONFLICT, "운영자금 잔액이 부족합니다.");
    if (normalized.signum() == 0 && !recordZero) return before;

    OffsetDateTime now = clock.instant().atOffset(ZoneOffset.UTC);
    db.update(
        "UPDATE treasury_accounts SET balance=?,updated_at=? WHERE account_type=?",
        after,
        now,
        accountType);
    db.update(
        "INSERT INTO treasury_ledger"
            + " (id,event_group_id,account_type,event_type,amount_delta,balance_after,reason,"
            + "source_type,source_id,created_by,created_at) VALUES (?,?,?,?,?,?,?,?,?,?,?)",
        UUID.randomUUID(),
        eventGroup,
        accountType,
        eventType,
        normalized,
        after,
        reason.strip(),
        sourceType,
        sourceId,
        actor,
        now);
    balances.put(accountType, after);
    return after;
  }
}
