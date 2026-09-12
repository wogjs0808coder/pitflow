package com.pitflow;

import static org.assertj.core.api.Assertions.*;

import java.math.BigDecimal;
import java.sql.DriverManager;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

class CatalogMigrationTest {
  @Test
  void upgradesV3WithoutResettingCatalogStockOrHistoryAndRunsOnlyOnce() throws Exception {
    String url =
        System.getenv()
            .getOrDefault(
                "TEST_DB_URL",
                "jdbc:h2:mem:catalog_upgrade;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE");
    String user = System.getenv().getOrDefault("TEST_DB_USERNAME", "sa");
    String password = System.getenv().getOrDefault("TEST_DB_PASSWORD", "");
    try (var connection = DriverManager.getConnection(url, user, password)) {
      String original = connection.getSchema(),
          schema = "test_catalog_" + UUID.randomUUID().toString().replace("-", "");
      var ds = new SingleConnectionDataSource(connection, true);
      var db = new JdbcTemplate(ds);
      db.execute("CREATE SCHEMA " + schema);
      connection.setSchema(schema);
      try {
        Flyway.configure().dataSource(ds).defaultSchema(schema).target("3").load().migrate();
        UUID part = UUID.randomUUID(),
            actor = UUID.randomUUID(),
            operation = UUID.randomUUID(),
            movement = UUID.randomUUID();
        db.update(
            "UPDATE service_items SET labor_price=12345,description='기존 설명',active=FALSE WHERE"
                + " name='배터리 교체'");
        db.update(
            "INSERT INTO parts (id,sku,name,unit,quantity,minimum_quantity,unit_price,active)"
                + " VALUES (?,'CUSTOM','자동차 12v 배터리','EA',5,2,999,TRUE)",
            part);
        db.update(
            "INSERT INTO users VALUES"
                + " (?,'migration@example.com','test-hash','관리자','ADMIN',CURRENT_TIMESTAMP)",
            actor);
        db.update(
            "INSERT INTO stock_operations VALUES (?,?,'test','{}',CURRENT_TIMESTAMP)",
            operation,
            actor);
        db.update(
            """
INSERT INTO stock_movements (id,operation_id,part_id,kind,quantity,balance_after,part_name,unit,unit_price,actor_id,reason,created_at)
VALUES (?,?,?,'RECEIPT',5,5,'기존 이름','EA',999,?,'기존 입고',CURRENT_TIMESTAMP)
""",
            movement,
            operation,
            part,
            actor);
        var latest = Flyway.configure().dataSource(ds).defaultSchema(schema).load();
        assertThat(latest.migrate().migrationsExecuted).isEqualTo(1);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM service_items", Integer.class))
            .isEqualTo(18);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM parts", Integer.class)).isEqualTo(17);
        assertThat(
                db.queryForObject("SELECT quantity FROM parts WHERE id=?", BigDecimal.class, part))
            .isEqualByComparingTo("5");
        assertThat(
                db.queryForObject(
                    "SELECT COUNT(*) FROM parts WHERE id<>? AND (quantity<>0 OR unit_price<>0)",
                    Integer.class,
                    part))
            .isZero();
        assertThat(
                db.queryForObject(
                    "SELECT labor_price FROM service_items WHERE name='배터리 교체'", BigDecimal.class))
            .isEqualByComparingTo("12345");
        assertThat(
                db.queryForObject(
                    "SELECT description FROM service_items WHERE name='배터리 교체'", String.class))
            .isEqualTo("기존 설명");
        assertThat(
                db.queryForObject(
                    "SELECT active FROM service_items WHERE name='배터리 교체'", Boolean.class))
            .isFalse();
        assertThat(
                db.queryForObject(
                    "SELECT part_name FROM stock_movements WHERE id=?", String.class, movement))
            .isEqualTo("기존 이름");
        assertThat(
                db.queryForObject(
                    "SELECT COUNT(*) FROM service_part_requirements WHERE part_id=?",
                    Integer.class,
                    part))
            .isEqualTo(1);
        assertThat(latest.migrate().migrationsExecuted).isZero();
        assertThat(
                db.queryForObject("SELECT quantity FROM parts WHERE id=?", BigDecimal.class, part))
            .isEqualByComparingTo("5");
      } finally {
        connection.setSchema(original);
        db.execute("DROP SCHEMA " + schema + " CASCADE");
      }
    }
  }
}
