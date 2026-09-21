package com.pitflow.finance;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class TreasurySettlementService {
  public static final BigDecimal DEPOSIT_DAILY_RATE =
      new BigDecimal("0.000133680617113496");
  private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

  private final JdbcTemplate db;
  private final TreasuryMutationService treasury;
  private final InvestmentReturnGenerator returns;
  private final TransactionTemplate tx;
  private final Clock clock;

  public TreasurySettlementService(
      JdbcTemplate db,
      TreasuryMutationService treasury,
      InvestmentReturnGenerator returns,
      PlatformTransactionManager manager,
      Clock clock) {
    this.db = db;
    this.treasury = treasury;
    this.returns = returns;
    this.tx = new TransactionTemplate(manager);
    this.tx.setTimeout(15);
    this.clock = clock;
  }

  @EventListener(ApplicationReadyEvent.class)
  public void initializeAndCatchUp() {
    // A supplied mutable Clock may not have received its initial instant yet.
    if (clock.instant() == null) return;
    settleThrough(seoulDate(clock));
  }

  @Scheduled(initialDelay = 3_600_000, fixedDelay = 3_600_000)
  public void scheduledCatchUp() {
    settleThrough(seoulDate(clock));
  }

  public static LocalDate seoulDate(Clock clock) {
    return clock.instant().atZone(SEOUL).toLocalDate();
  }

  public int settleThrough(LocalDate throughDate) {
    int settled = 0;
    while (Boolean.TRUE.equals(tx.execute(status -> settleNext(throughDate)))) settled++;
    return settled;
  }

  private boolean settleNext(LocalDate throughDate) {
    Map<String, Object> state =
        db.queryForMap("SELECT * FROM treasury_simulation_state WHERE id=1 FOR UPDATE");
    OffsetDateTime now = clock.instant().atOffset(ZoneOffset.UTC);
    if (state.get("activation_date") == null) {
      db.update(
          "UPDATE treasury_simulation_state SET activation_date=?,last_settled_date=?,updated_at=?"
              + " WHERE id=1",
          throughDate,
          throughDate,
          now);
      return false;
    }

    LocalDate last = LocalDate.parse(state.get("last_settled_date").toString());
    LocalDate date = last.plusDays(1);
    if (date.isAfter(throughDate)) return false;

    Map<String, BigDecimal> balances = treasury.lockBalances();
    BigDecimal depositOpening = balances.get("DEPOSIT");
    BigDecimal investmentOpening = balances.get("INVESTMENT");
    BigDecimal depositInterest =
        depositOpening.multiply(DEPOSIT_DAILY_RATE).setScale(0, RoundingMode.HALF_UP);
    BigDecimal investmentRate = returns.nextRate().setScale(4, RoundingMode.UNNECESSARY);
    if (investmentRate.compareTo(new BigDecimal("-0.0300")) < 0
        || investmentRate.compareTo(new BigDecimal("0.0500")) > 0)
      throw new IllegalStateException("Investment return is outside the configured range");
    BigDecimal investmentAmount =
        investmentOpening.multiply(investmentRate).setScale(0, RoundingMode.HALF_UP);
    if (investmentOpening.add(investmentAmount).signum() < 0)
      throw new IllegalStateException("Investment settlement cannot create a negative balance");

    UUID eventGroup = UUID.randomUUID();
    treasury.changeLocked(
        balances,
        "DEPOSIT",
        depositInterest,
        "DEPOSIT_INTEREST",
        date + " 예금 일복리 정산",
        "DAILY_SETTLEMENT",
        eventGroup,
        null,
        eventGroup,
        true);
    treasury.changeLocked(
        balances,
        "INVESTMENT",
        investmentAmount,
        "INVESTMENT_RETURN",
        date + " 투자 수익률 정산",
        "DAILY_SETTLEMENT",
        eventGroup,
        null,
        eventGroup,
        true);

    db.update(
        "INSERT INTO treasury_daily_settlements"
            + " (settlement_date,event_group_id,deposit_opening_balance,deposit_daily_rate,"
            + "deposit_interest,deposit_closing_balance,investment_opening_balance,"
            + "investment_return_rate,investment_return_amount,investment_closing_balance,"
            + "created_at) VALUES (?,?,?,?,?,?,?,?,?,?,?)",
        date,
        eventGroup,
        depositOpening,
        DEPOSIT_DAILY_RATE,
        depositInterest,
        balances.get("DEPOSIT"),
        investmentOpening,
        investmentRate,
        investmentAmount,
        balances.get("INVESTMENT"),
        now);
    db.update(
        "UPDATE treasury_simulation_state SET last_settled_date=?,updated_at=? WHERE id=1",
        date,
        now);
    return true;
  }
}
