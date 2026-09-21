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

class TreasuryAssetsMigrationTest {
  @Test
  void v22PreservesTreasuryAndAddsSimulationAndCashMetadata() throws Exception {
    String url =
        System.getenv()
            .getOrDefault(
                "TEST_DB_URL",
                "jdbc:h2:mem:treasury_assets_upgrade;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE");
    try (var connection =
        DriverManager.getConnection(
            url,
            System.getenv().getOrDefault("TEST_DB_USERNAME", "sa"),
            System.getenv().getOrDefault("TEST_DB_PASSWORD", ""))) {
      String original = connection.getSchema();
      String schema = "test_treasury_assets_" + UUID.randomUUID().toString().replace("-", "");
      var source = new SingleConnectionDataSource(connection, true);
      var db = new JdbcTemplate(source);
      db.execute("CREATE SCHEMA " + schema);
      connection.setSchema(schema);
      try {
        Flyway.configure().dataSource(source).defaultSchema(schema).target("21").load().migrate();
        assertThat(db.queryForObject("SELECT COUNT(*) FROM treasury_ledger", Integer.class))
            .isEqualTo(3);
        UUID actor = UUID.randomUUID();
        db.update(
            "INSERT INTO users (id,email,password_hash,name,role,created_at)"
                + " VALUES (?,'historical-finance@example.com','hash','과거 관리자','ADMIN',"
                + "CURRENT_TIMESTAMP)",
            actor);
        db.update(
            "INSERT INTO finance_entries"
                + " (id,entry_date,category,amount,description,entry_kind,original_entry_id,"
                + "created_by,created_at) VALUES (?,CURRENT_DATE,'RENT',1000,'과거 임차료',"
                + "'ENTRY',NULL,?,CURRENT_TIMESTAMP)",
            UUID.randomUUID(),
            actor);
        var latest = Flyway.configure().dataSource(source).defaultSchema(schema).target("22").load();
        assertThat(latest.migrate().migrationsExecuted).isOne();
        assertThat(db.queryForObject("SELECT COUNT(*) FROM treasury_accounts", Integer.class))
            .isEqualTo(3);
        assertThat(
                db.queryForObject(
                    "SELECT SUM(balance) FROM treasury_accounts", BigDecimal.class))
            .isEqualByComparingTo("1000000000");
        assertThat(
                db.queryForObject(
                    "SELECT COUNT(*) FROM treasury_ledger"
                        + " WHERE event_type='OPENING_ALLOCATION'",
                    Integer.class))
            .isEqualTo(3);
        assertThat(
                db.queryForObject(
                    "SELECT COUNT(*) FROM treasury_simulation_state WHERE id=1",
                    Integer.class))
            .isOne();
        assertThat(
                db.queryForObject(
                    "SELECT affects_treasury FROM finance_entries", Boolean.class))
            .isFalse();
        assertThat(db.queryForObject("SELECT COUNT(*) FROM treasury_ledger", Integer.class))
            .isEqualTo(3);
        db.update(
            "INSERT INTO treasury_ledger"
                + " (id,event_group_id,account_type,event_type,amount_delta,balance_after,reason,"
                + "source_type,source_id,created_by,created_at)"
                + " VALUES (?,?, 'OPERATING','INVENTORY_PURCHASE',0,400000000,'test',?,?,NULL,"
                + "CURRENT_TIMESTAMP)",
            UUID.randomUUID(),
            UUID.randomUUID(),
            "STOCK_MOVEMENT",
            UUID.randomUUID());
        assertThatThrownBy(
                () ->
                    db.update(
                        "INSERT INTO treasury_daily_settlements VALUES"
                            + " (CURRENT_DATE,?,300000000,0.000133680617113496,40104,300040104,"
                            + "300000000,0.06,18000000,318000000,CURRENT_TIMESTAMP)",
                        UUID.randomUUID()))
            .isInstanceOf(Exception.class);
        UUID settlementGroup = UUID.randomUUID();
        db.update(
            "INSERT INTO treasury_daily_settlements VALUES"
                + " (CURRENT_DATE,?,300000000,0.000133680617113496,40104,300040104,"
                + "300000000,0.01,3000000,303000000,CURRENT_TIMESTAMP)",
            settlementGroup);
        assertThatThrownBy(
                () ->
                    db.update(
                        "INSERT INTO treasury_daily_settlements VALUES"
                            + " (CURRENT_DATE,?,300040104,0.000133680617113496,40109,300080213,"
                            + "303000000,0.01,3030000,306030000,CURRENT_TIMESTAMP)",
                        UUID.randomUUID()))
            .isInstanceOf(Exception.class);
        assertThatThrownBy(
                () ->
                    db.update(
                        "INSERT INTO treasury_ledger"
                            + " (id,event_group_id,account_type,event_type,amount_delta,"
                            + "balance_after,reason,source_type,source_id,created_by,created_at)"
                            + " VALUES (?,?,'OPERATING','NOT_AN_EVENT',0,400000000,'test',"
                            + "NULL,NULL,NULL,CURRENT_TIMESTAMP)",
                        UUID.randomUUID(),
                        UUID.randomUUID()))
            .isInstanceOf(Exception.class);
        assertThatThrownBy(
                () ->
                    db.update(
                        "INSERT INTO treasury_ledger"
                            + " (id,event_group_id,account_type,event_type,amount_delta,"
                            + "balance_after,reason,source_type,source_id,created_by,created_at)"
                            + " VALUES (?,?,'OPERATING','OPERATING_INCOME',0,400000000,'test',"
                            + "NULL,?,NULL,CURRENT_TIMESTAMP)",
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        UUID.randomUUID()))
            .isInstanceOf(Exception.class);
        assertThatThrownBy(
                () ->
                    db.update(
                        "UPDATE treasury_accounts SET balance=-1"
                            + " WHERE account_type='OPERATING'"))
            .isInstanceOf(Exception.class);
        assertThat(latest.migrate().migrationsExecuted).isZero();
        latest.validate();
      } finally {
        connection.setSchema(original);
        db.execute("DROP SCHEMA " + schema + " CASCADE");
      }
    }
  }
}
