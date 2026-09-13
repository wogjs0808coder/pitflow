package com.pitflow;

import static org.assertj.core.api.Assertions.*;

import java.sql.DriverManager;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

class BillingMigrationTest {
  @Test
  void upgradesV4AndPreservesCatalogAndExistingMigrationChecksums() throws Exception {
    String url =
        System.getenv()
            .getOrDefault(
                "TEST_DB_URL",
                "jdbc:h2:mem:billing_upgrade;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE");
    try (var c =
        DriverManager.getConnection(
            url,
            System.getenv().getOrDefault("TEST_DB_USERNAME", "sa"),
            System.getenv().getOrDefault("TEST_DB_PASSWORD", ""))) {
      String original = c.getSchema(),
          schema = "test_billing_" + UUID.randomUUID().toString().replace("-", "");
      var ds = new SingleConnectionDataSource(c, true);
      var db = new JdbcTemplate(ds);
      db.execute("CREATE SCHEMA " + schema);
      c.setSchema(schema);
      try {
        Flyway.configure().dataSource(ds).defaultSchema(schema).target("4").load().migrate();
        var catalog = db.queryForList("SELECT * FROM service_items ORDER BY id");
        var parts = db.queryForList("SELECT * FROM parts ORDER BY id");
        var checks =
            db.queryForList(
                "SELECT version,checksum FROM \"flyway_schema_history\" WHERE success=TRUE ORDER BY"
                    + " installed_rank");
        var latest = Flyway.configure().dataSource(ds).defaultSchema(schema).load();
        assertThat(latest.migrate().migrationsExecuted).isEqualTo(1);
        latest.validate();
        assertThat(db.queryForList("SELECT * FROM service_items ORDER BY id")).isEqualTo(catalog);
        assertThat(db.queryForList("SELECT * FROM parts ORDER BY id")).isEqualTo(parts);
        assertThat(
                db.queryForList(
                    "SELECT version,checksum FROM \"flyway_schema_history\" WHERE success=TRUE AND"
                        + " (version IS NULL OR version<>'5') ORDER BY installed_rank"))
            .isEqualTo(checks);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM invoices", Integer.class)).isZero();
        assertThat(db.queryForObject("SELECT COUNT(*) FROM payment_records", Integer.class))
            .isZero();
        assertThat(latest.migrate().migrationsExecuted).isZero();
      } finally {
        c.setSchema(original);
        db.execute("DROP SCHEMA " + schema + " CASCADE");
      }
    }
  }
}
