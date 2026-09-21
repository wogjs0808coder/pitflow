package com.pitflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.sql.DriverManager;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

class TreasuryMigrationTest {
  @Test
  void v21CreatesBalancedOpeningTreasuryWithoutChangingEarlierSchema() throws Exception {
    String url =
        System.getenv()
            .getOrDefault(
                "TEST_DB_URL",
                "jdbc:h2:mem:treasury_upgrade;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE");
    try (var connection =
        DriverManager.getConnection(
            url,
            System.getenv().getOrDefault("TEST_DB_USERNAME", "sa"),
            System.getenv().getOrDefault("TEST_DB_PASSWORD", ""))) {
      String original = connection.getSchema();
      String schema = "test_treasury_" + UUID.randomUUID().toString().replace("-", "");
      var source = new SingleConnectionDataSource(connection, true);
      var db = new JdbcTemplate(source);
      db.execute("CREATE SCHEMA " + schema);
      connection.setSchema(schema);
      try {
        Flyway.configure().dataSource(source).defaultSchema(schema).target("20").load().migrate();
        var latest = Flyway.configure().dataSource(source).defaultSchema(schema).target("21").load();
        assertThat(latest.migrate().migrationsExecuted).isOne();
        assertThat(db.queryForObject("SELECT COUNT(*) FROM treasury_accounts", Integer.class))
            .isEqualTo(3);
        assertThat(
                db.queryForObject(
                    "SELECT SUM(balance) FROM treasury_accounts", BigDecimal.class))
            .isEqualByComparingTo("1000000000");
        assertThat(balance(db, "OPERATING")).isEqualByComparingTo("400000000");
        assertThat(balance(db, "DEPOSIT")).isEqualByComparingTo("300000000");
        assertThat(balance(db, "INVESTMENT")).isEqualByComparingTo("300000000");
        assertThat(ratio(db, "OPERATING")).isEqualByComparingTo("0.40");
        assertThat(ratio(db, "DEPOSIT")).isEqualByComparingTo("0.30");
        assertThat(ratio(db, "INVESTMENT")).isEqualByComparingTo("0.30");
        assertThat(
                db.queryForObject(
                    "SELECT COUNT(*) FROM treasury_ledger WHERE event_type='OPENING_ALLOCATION'",
                    Integer.class))
            .isEqualTo(3);
        assertThat(
                db.queryForObject(
                    "SELECT SUM(amount_delta) FROM treasury_ledger"
                        + " WHERE event_type='OPENING_ALLOCATION'",
                    BigDecimal.class))
            .isEqualByComparingTo("1000000000");
        assertThat(
                db.queryForObject(
                    "SELECT COUNT(*) FROM treasury_ledger l JOIN treasury_accounts a"
                        + " ON a.account_type=l.account_type"
                        + " WHERE l.event_type='OPENING_ALLOCATION'"
                        + " AND l.balance_after=a.balance",
                    Integer.class))
            .isEqualTo(3);
        assertThatThrownBy(
                () ->
                    db.update(
                        "UPDATE treasury_accounts SET balance=-1 WHERE account_type='OPERATING'"))
            .isInstanceOf(Exception.class);
        assertThat(latest.migrate().migrationsExecuted).isZero();
        latest.validate();
      } finally {
        connection.setSchema(original);
        db.execute("DROP SCHEMA " + schema + " CASCADE");
      }
    }
  }

  private BigDecimal balance(JdbcTemplate db, String type) {
    return db.queryForObject(
        "SELECT balance FROM treasury_accounts WHERE account_type=?", BigDecimal.class, type);
  }

  private BigDecimal ratio(JdbcTemplate db, String type) {
    return db.queryForObject(
        "SELECT target_ratio FROM treasury_accounts WHERE account_type=?",
        BigDecimal.class,
        type);
  }
}
