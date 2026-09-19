package com.pitflow;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.sql.DriverManager;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

class InventoryCostMigrationTest {
  @Test
  void v17CreatesUnknownOpeningLotsMatchingExistingInventory() throws Exception {
    String url =
        System.getenv()
            .getOrDefault(
                "TEST_DB_URL",
                "jdbc:h2:mem:inventory_cost_upgrade;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE");
    try (var connection =
        DriverManager.getConnection(
            url,
            System.getenv().getOrDefault("TEST_DB_USERNAME", "sa"),
            System.getenv().getOrDefault("TEST_DB_PASSWORD", ""))) {
      String original = connection.getSchema();
      String schema = "test_inventory_cost_" + UUID.randomUUID().toString().replace("-", "");
      var dataSource = new SingleConnectionDataSource(connection, true);
      var db = new JdbcTemplate(dataSource);
      db.execute("CREATE SCHEMA " + schema);
      connection.setSchema(schema);
      try {
        Flyway.configure().dataSource(dataSource).defaultSchema(schema).target("16").load().migrate();
        UUID stocked =
            db.queryForObject("SELECT id FROM parts ORDER BY id LIMIT 1", UUID.class);
        db.update("UPDATE parts SET quantity=3.500 WHERE id=?", stocked);

        var latest =
            Flyway.configure().dataSource(dataSource).defaultSchema(schema).target("17").load();
        assertThat(latest.migrate().migrationsExecuted).isEqualTo(1);

        var opening =
            db.queryForMap("SELECT * FROM inventory_cost_lots WHERE part_id=?", stocked);
        assertThat(opening.get("origin")).isEqualTo("OPENING");
        assertThat(opening.get("source_movement_id")).isNull();
        assertThat(opening.get("cost_known")).isEqualTo(false);
        assertThat(opening.get("purchase_unit_cost")).isNull();
        assertThat((BigDecimal) opening.get("original_quantity")).isEqualByComparingTo("3.500");
        assertThat((BigDecimal) opening.get("remaining_quantity")).isEqualByComparingTo("3.500");
        assertThat(
                db.queryForObject(
                    "SELECT COUNT(*) FROM parts p WHERE p.quantity <> (SELECT COALESCE(SUM(l.remaining_quantity),0) FROM inventory_cost_lots l WHERE l.part_id=p.id)",
                    Integer.class))
            .isZero();
        assertThat(latest.migrate().migrationsExecuted).isZero();
        latest.validate();
      } finally {
        connection.setSchema(original);
        db.execute("DROP SCHEMA " + schema + " CASCADE");
      }
    }
  }
}
