package com.pitflow;

import static org.assertj.core.api.Assertions.*;

import java.math.BigDecimal;
import java.sql.DriverManager;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

class PartsDescriptionMigrationTest {
  @Test
  void upgradesV7WithoutOverwritingEnteredPricesAndAddsDemoDescriptions() throws Exception {
    String url =
        System.getenv()
            .getOrDefault(
                "TEST_DB_URL",
                "jdbc:h2:mem:part_description_upgrade;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE");
    try (var connection =
        DriverManager.getConnection(
            url,
            System.getenv().getOrDefault("TEST_DB_USERNAME", "sa"),
            System.getenv().getOrDefault("TEST_DB_PASSWORD", ""))) {
      String original = connection.getSchema(),
          schema = "test_part_description_" + UUID.randomUUID().toString().replace("-", "");
      var ds = new SingleConnectionDataSource(connection, true);
      var db = new JdbcTemplate(ds);
      db.execute("CREATE SCHEMA " + schema);
      connection.setSchema(schema);
      try {
        Flyway.configure().dataSource(ds).defaultSchema(schema).target("7").load().migrate();
        UUID custom = UUID.randomUUID();
        db.update(
            "INSERT INTO parts"
                + " (id,sku,name,unit,quantity,minimum_quantity,unit_price,active,archived)"
                + " VALUES (?,'CUSTOM','사용자 부품','EA',3,1,77777,TRUE,FALSE)",
            custom);
        db.update("UPDATE parts SET unit_price=32100 WHERE sku='PF-BATTERY'");

        var latest = Flyway.configure().dataSource(ds).defaultSchema(schema).target("8").load();
        assertThat(latest.migrate().migrationsExecuted).isEqualTo(1);
        latest.validate();
        assertThat(
                db.queryForObject(
                    "SELECT unit_price FROM parts WHERE id=?", BigDecimal.class, custom))
            .isEqualByComparingTo("77777");
        assertThat(
                db.queryForObject("SELECT description FROM parts WHERE id=?", String.class, custom))
            .isEmpty();
        assertThat(
                db.queryForObject(
                    "SELECT unit_price FROM parts WHERE sku='PF-BATTERY'", BigDecimal.class))
            .isEqualByComparingTo("32100");
        assertThat(
                db.queryForObject(
                    "SELECT unit_price FROM parts WHERE sku='PF-OIL'", BigDecimal.class))
            .isEqualByComparingTo("12000");
        assertThat(
                db.queryForObject("SELECT description FROM parts WHERE sku='PF-OIL'", String.class))
            .contains("차량별 점도");
        assertThat(latest.migrate().migrationsExecuted).isZero();
      } finally {
        connection.setSchema(original);
        db.execute("DROP SCHEMA " + schema + " CASCADE");
      }
    }
  }
}
