package com.pitflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.pitflow.finance.TreasuryService;
import com.pitflow.finance.InvestmentReturnGenerator;
import com.pitflow.finance.TreasurySettlementService;
import com.pitflow.user.AppUser;
import com.pitflow.user.UserRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest
@ActiveProfiles("test")
class TreasurySettlementIntegrationTest {
  @Autowired JdbcTemplate db;
  @Autowired TreasurySettlementService settlements;
  @Autowired TreasuryService treasury;
  @Autowired UserRepository users;
  @MockitoBean InvestmentReturnGenerator returns;

  private final LocalDate activation = LocalDate.of(2026, 9, 20);

  @BeforeEach
  void setup() {
    reset();
  }

  @AfterEach
  void clean() {
    reset();
  }

  private void reset() {
    db.update("DELETE FROM users WHERE email='settlement-read@example.com'");
    db.update("DELETE FROM treasury_daily_settlements");
    db.update("DELETE FROM treasury_ledger WHERE event_type<>'OPENING_ALLOCATION'");
    db.update(
        "UPDATE treasury_accounts SET balance=CASE account_type"
            + " WHEN 'OPERATING' THEN 400000000 WHEN 'DEPOSIT' THEN 300000000"
            + " ELSE 300000000 END,updated_at=CURRENT_TIMESTAMP");
    db.update(
        "UPDATE treasury_simulation_state SET activation_date=?,last_settled_date=?,"
            + "updated_at=CURRENT_TIMESTAMP WHERE id=1",
        activation,
        activation);
  }

  @Test
  void effectiveAnnualDepositRateRoundsAndBothAssetsCompoundAcrossCatchUp() {
    double annual = Math.pow(BigDecimal.ONE.add(TreasurySettlementService.DEPOSIT_DAILY_RATE)
        .doubleValue(), 365) - 1;
    assertThat(annual).isCloseTo(0.05, org.assertj.core.data.Offset.offset(0.000000001));
    when(returns.nextRate()).thenReturn(new BigDecimal("0.0100"));

    assertThat(settlements.settleThrough(activation.plusDays(3))).isEqualTo(3);
    assertThat(count("treasury_daily_settlements")).isEqualTo(3);
    assertThat(balance("DEPOSIT")).isEqualByComparingTo("300120329");
    assertThat(balance("INVESTMENT")).isEqualByComparingTo("309090300");
    assertThat(
            db.queryForObject(
                "SELECT deposit_interest FROM treasury_daily_settlements"
                    + " WHERE settlement_date=?",
                BigDecimal.class,
                activation.plusDays(1)))
        .isEqualByComparingTo("40104");
    assertThat(count("treasury_ledger WHERE event_type='DEPOSIT_INTEREST'"))
        .isEqualTo(3);
    assertThat(count("treasury_ledger WHERE event_type='INVESTMENT_RETURN'"))
        .isEqualTo(3);
    assertThat(settlements.settleThrough(activation.plusDays(3))).isZero();
    assertThat(count("treasury_daily_settlements")).isEqualTo(3);
  }

  @Test
  void investmentSupportsPositiveNegativeAndZeroPersistedDailyRates() {
    when(returns.nextRate())
        .thenReturn(new BigDecimal("0.0500"), new BigDecimal("-0.0300"), BigDecimal.ZERO);
    assertThat(settlements.settleThrough(activation.plusDays(3))).isEqualTo(3);
    assertThat(balance("INVESTMENT")).isEqualByComparingTo("305550000");
    assertThat(
            db.queryForList(
                    "SELECT investment_return_rate,investment_return_amount"
                        + " FROM treasury_daily_settlements ORDER BY settlement_date")
                .stream()
                .map(row -> row.get("investment_return_rate") + ":" + row.get("investment_return_amount")))
        .containsExactly("0.050000:15000000", "-0.030000:-9450000", "0.000000:0");
  }

  @Test
  void concurrentSameDaySettlementAppliesExactlyOnce() throws Exception {
    when(returns.nextRate()).thenReturn(new BigDecimal("0.0100"));
    var gate = new CountDownLatch(1);
    var pool = Executors.newFixedThreadPool(2);
    try {
      var first = pool.submit(() -> { gate.await(); return settlements.settleThrough(activation.plusDays(1)); });
      var second = pool.submit(() -> { gate.await(); return settlements.settleThrough(activation.plusDays(1)); });
      gate.countDown();
      assertThat(first.get(10, TimeUnit.SECONDS) + second.get(10, TimeUnit.SECONDS)).isOne();
    } finally {
      pool.shutdownNow();
    }
    assertThat(count("treasury_daily_settlements")).isOne();
    assertThat(balance("DEPOSIT")).isEqualByComparingTo("300040104");
    assertThat(balance("INVESTMENT")).isEqualByComparingTo("303000000");
  }

  @Test
  void settlementFailureRollsBackAccountsLedgerSettlementAndState() {
    db.update(
        "UPDATE treasury_accounts SET balance=9900000000000000000"
            + " WHERE account_type IN ('DEPOSIT','INVESTMENT')");
    when(returns.nextRate()).thenReturn(new BigDecimal("0.0500"));
    assertThatThrownBy(() -> settlements.settleThrough(activation.plusDays(1)))
        .isInstanceOf(RuntimeException.class);
    assertThat(balance("DEPOSIT")).isEqualByComparingTo("9900000000000000000");
    assertThat(balance("INVESTMENT")).isEqualByComparingTo("9900000000000000000");
    assertThat(count("treasury_daily_settlements")).isZero();
    assertThat(count("treasury_ledger WHERE event_type<>'OPENING_ALLOCATION'"))
        .isZero();
    assertThat(
            db.queryForObject(
                "SELECT last_settled_date FROM treasury_simulation_state WHERE id=1",
                LocalDate.class))
        .isEqualTo(activation);
  }

  @Test
  void repeatedTreasuryReadsDoNotSettleAgainOrRegenerateThePersistedRate() {
    String email = "settlement-read@example.com";
    users.saveAndFlush(new AppUser(email, "hash", "정산 조회", AppUser.Role.ADMIN));
    when(returns.nextRate()).thenReturn(new BigDecimal("0.0123"));
    settlements.settleThrough(activation.plusDays(1));
    BigDecimal investment = balance("INVESTMENT");
    BigDecimal rate =
        db.queryForObject(
            "SELECT investment_return_rate FROM treasury_daily_settlements",
            BigDecimal.class);

    treasury.treasury(email);
    treasury.treasury(email);

    assertThat(count("treasury_daily_settlements")).isOne();
    assertThat(balance("INVESTMENT")).isEqualByComparingTo(investment);
    assertThat(
            db.queryForObject(
                "SELECT investment_return_rate FROM treasury_daily_settlements",
                BigDecimal.class))
        .isEqualByComparingTo(rate);
  }

  @Test
  void seoulCalendarBoundaryUsesAsiaSeoulInsteadOfUtcDate() {
    Clock beforeMidnight =
        Clock.fixed(Instant.parse("2026-09-21T14:59:59Z"), ZoneOffset.UTC);
    Clock afterMidnight =
        Clock.fixed(Instant.parse("2026-09-21T15:00:00Z"), ZoneOffset.UTC);

    assertThat(TreasurySettlementService.seoulDate(beforeMidnight))
        .isEqualTo(LocalDate.of(2026, 9, 21));
    assertThat(TreasurySettlementService.seoulDate(afterMidnight))
        .isEqualTo(LocalDate.of(2026, 9, 22));
  }

  private BigDecimal balance(String type) {
    return db.queryForObject(
        "SELECT balance FROM treasury_accounts WHERE account_type=?", BigDecimal.class, type);
  }

  private int count(String tableAndWhere) {
    return db.queryForObject("SELECT COUNT(*) FROM " + tableAndWhere, Integer.class);
  }
}
