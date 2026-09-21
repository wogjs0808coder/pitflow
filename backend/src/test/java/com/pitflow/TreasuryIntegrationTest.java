package com.pitflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pitflow.user.AppUser;
import com.pitflow.user.UserRepository;
import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class TreasuryIntegrationTest {
  @Autowired MockMvc mvc;
  @Autowired ObjectMapper json;
  @Autowired JdbcTemplate db;
  @Autowired UserRepository users;

  private final String admin = "treasury-admin@example.com";

  @BeforeEach
  void setup() {
    clean();
    users.saveAndFlush(new AppUser(admin, "hash", "관리자", AppUser.Role.ADMIN));
    users.saveAndFlush(
        new AppUser("treasury-customer@example.com", "hash", "고객", AppUser.Role.CUSTOMER));
    users.saveAndFlush(
        new AppUser("treasury-mechanic@example.com", "hash", "정비사", AppUser.Role.MECHANIC));
  }

  @AfterEach
  void clean() {
    db.update("DELETE FROM treasury_ledger WHERE event_type<>'OPENING_ALLOCATION'");
    db.update(
        "DELETE FROM stock_operations WHERE actor_id IN"
            + " (SELECT id FROM users WHERE email IN (?, ?, ?))",
        admin,
        "treasury-customer@example.com",
        "treasury-mechanic@example.com");
    db.update(
        "UPDATE treasury_accounts SET balance=CASE account_type"
            + " WHEN 'OPERATING' THEN 400000000"
            + " WHEN 'DEPOSIT' THEN 300000000 ELSE 300000000 END,"
            + " target_ratio=CASE account_type"
            + " WHEN 'OPERATING' THEN 0.40 WHEN 'DEPOSIT' THEN 0.30 ELSE 0.30 END,"
            + " updated_at=CURRENT_TIMESTAMP");
    db.update(
        "DELETE FROM users WHERE email IN (?, ?, ?)",
        admin,
        "treasury-customer@example.com",
        "treasury-mechanic@example.com");
  }

  @Test
  void adminReadsTreasuryWhileOtherRolesAreForbidden() throws Exception {
    JsonNode value = readAdmin();
    assertThat(value.get("total_assets").decimalValue()).isEqualByComparingTo("1000000000");
    assertThat(value.get("accounts").get("OPERATING").get("balance").decimalValue())
        .isEqualByComparingTo("400000000");
    assertThat(value.get("accounts").get("DEPOSIT").get("target_ratio").decimalValue())
        .isEqualByComparingTo("30");
    assertThat(value.get("accounts").get("INVESTMENT").get("current_ratio").decimalValue())
        .isEqualByComparingTo("30");
    assertThat(value.get("can_rebalance").asBoolean()).isFalse();
    assertThat(value.get("last_updated_at").asText()).isNotBlank();

    for (String role : new String[] {"CUSTOMER", "MECHANIC"}) {
        assertThat(
                mvc.perform(
                        get("/api/admin/finance/treasury")
                            .with(user("blocked@example.com").roles(role)))
                    .andReturn()
                    .getResponse()
                    .getStatus())
            .isEqualTo(403);
        assertThat(
                mvc.perform(
                        post("/api/admin/finance/treasury/rebalance")
                            .with(user("blocked@example.com").roles(role))
                            .with(csrf())
                            .header("Idempotency-Key", UUID.randomUUID()))
                    .andReturn()
                    .getResponse()
                    .getStatus())
            .isEqualTo(403);
      }
    assertThat(
            mvc.perform(
                    post("/api/admin/finance/treasury/rebalance")
                        .with(user(admin).roles("ADMIN"))
                        .header("Idempotency-Key", UUID.randomUUID()))
                .andReturn()
                .getResponse()
                .getStatus())
        .isEqualTo(403);
  }

  @Test
  void balancedRebalanceIsIdempotentNoOpWithoutLedgerRows() throws Exception {
    UUID key = UUID.randomUUID();
    JsonNode first = rebalance(key);
    JsonNode replay = rebalance(key);
    assertThat(replay).isEqualTo(first);
    assertThat(first.get("can_rebalance").asBoolean()).isFalse();
    assertThat(rebalanceLedgerCount()).isZero();
    assertThat(
            mvc.perform(post("/api/admin/finance/treasury/rebalance")
                    .with(user(admin).roles("ADMIN"))
                    .with(csrf()))
                .andReturn()
                .getResponse()
                .getStatus())
        .isEqualTo(400);
  }

  @Test
  void rebalancePreservesTotalUsesRemainderAndReplaysWithoutDuplicateLedger()
      throws Exception {
    setBalances("384000000", "289000000", "327000001");
    UUID key = UUID.randomUUID();
    JsonNode first = rebalance(key);
    assertThat(first.get("total_assets").decimalValue()).isEqualByComparingTo("1000000001");
    assertThat(first.get("accounts").get("OPERATING").get("balance").decimalValue())
        .isEqualByComparingTo("400000000");
    assertThat(first.get("accounts").get("DEPOSIT").get("balance").decimalValue())
        .isEqualByComparingTo("300000000");
    assertThat(first.get("accounts").get("INVESTMENT").get("balance").decimalValue())
        .isEqualByComparingTo("300000001");
    assertThat(first.get("can_rebalance").asBoolean()).isFalse();
    assertThat(rebalanceLedgerCount()).isEqualTo(3);
    assertThat(
            db.queryForObject(
                "SELECT COUNT(DISTINCT event_group_id) FROM treasury_ledger"
                    + " WHERE event_type='REBALANCE'",
                Integer.class))
        .isOne();
    assertThat(
            db.queryForObject(
                "SELECT SUM(amount_delta) FROM treasury_ledger WHERE event_type='REBALANCE'",
                BigDecimal.class))
        .isEqualByComparingTo("0");
    assertThat(
            db.queryForObject(
                "SELECT COUNT(*) FROM treasury_ledger l JOIN treasury_accounts a"
                    + " ON a.account_type=l.account_type"
                    + " WHERE l.event_type='REBALANCE' AND l.balance_after=a.balance",
                Integer.class))
        .isEqualTo(3);
    assertThat(rebalance(key)).isEqualTo(first);
    assertThat(rebalanceLedgerCount()).isEqualTo(3);
  }

  @Test
  void concurrentRebalancesSerializeWithoutCorruptingBalances() throws Exception {
    setBalances("500000000", "200000000", "300000000");
    var gate = new CountDownLatch(1);
    var pool = Executors.newFixedThreadPool(2);
    try {
      var first = pool.submit(() -> concurrentRebalance(gate, UUID.randomUUID()));
      var second = pool.submit(() -> concurrentRebalance(gate, UUID.randomUUID()));
      gate.countDown();
      assertThat(first.get(15, TimeUnit.SECONDS).getResponse().getStatus()).isEqualTo(200);
      assertThat(second.get(15, TimeUnit.SECONDS).getResponse().getStatus()).isEqualTo(200);
    } finally {
      pool.shutdownNow();
    }
    assertThat(balance("OPERATING")).isEqualByComparingTo("400000000");
    assertThat(balance("DEPOSIT")).isEqualByComparingTo("300000000");
    assertThat(balance("INVESTMENT")).isEqualByComparingTo("300000000");
    assertThat(total()).isEqualByComparingTo("1000000000");
    assertThat(rebalanceLedgerCount()).isEqualTo(3);
    assertThat(
            db.queryForObject(
                "SELECT SUM(amount_delta) FROM treasury_ledger WHERE event_type='REBALANCE'",
                BigDecimal.class))
        .isEqualByComparingTo("0");
  }

  @Test
  void ledgerFailureRollsBackAccountUpdatesAndCommandReservation() throws Exception {
    String large = "9000000000000000000";
    setBalances(large, large, large);
    MvcResult result = postRebalance(UUID.randomUUID());
    assertThat(result.getResponse().getStatus()).isEqualTo(409);
    assertThat(balance("OPERATING")).isEqualByComparingTo(large);
    assertThat(balance("DEPOSIT")).isEqualByComparingTo(large);
    assertThat(balance("INVESTMENT")).isEqualByComparingTo(large);
    assertThat(rebalanceLedgerCount()).isZero();
    assertThat(
            db.queryForObject(
                "SELECT COUNT(*) FROM stock_operations WHERE actor_id="
                    + "(SELECT id FROM users WHERE email=?)",
                Integer.class,
                admin))
        .isZero();
  }

  private MvcResult concurrentRebalance(CountDownLatch gate, UUID key) throws Exception {
    gate.await(5, TimeUnit.SECONDS);
    return postRebalance(key);
  }

  private JsonNode readAdmin() throws Exception {
    MvcResult result =
        mvc.perform(get("/api/admin/finance/treasury").with(user(admin).roles("ADMIN")))
            .andReturn();
    assertThat(result.getResponse().getStatus()).isEqualTo(200);
    return json.readTree(result.getResponse().getContentAsString());
  }

  private JsonNode rebalance(UUID key) throws Exception {
    MvcResult result = postRebalance(key);
    assertThat(result.getResponse().getStatus())
        .withFailMessage(result.getResponse().getContentAsString())
        .isEqualTo(200);
    return json.readTree(result.getResponse().getContentAsString());
  }

  private MvcResult postRebalance(UUID key) throws Exception {
    return mvc.perform(
            post("/api/admin/finance/treasury/rebalance")
                .with(user(admin).roles("ADMIN"))
                .with(csrf())
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andReturn();
  }

  private void setBalances(String operating, String deposit, String investment) {
    db.update(
        "UPDATE treasury_accounts SET balance=CASE account_type"
            + " WHEN 'OPERATING' THEN ? WHEN 'DEPOSIT' THEN ? ELSE ? END",
        new BigDecimal(operating),
        new BigDecimal(deposit),
        new BigDecimal(investment));
  }

  private BigDecimal balance(String type) {
    return db.queryForObject(
        "SELECT balance FROM treasury_accounts WHERE account_type=?", BigDecimal.class, type);
  }

  private BigDecimal total() {
    return db.queryForObject("SELECT SUM(balance) FROM treasury_accounts", BigDecimal.class);
  }

  private int rebalanceLedgerCount() {
    return db.queryForObject(
        "SELECT COUNT(*) FROM treasury_ledger WHERE event_type='REBALANCE'", Integer.class);
  }
}
