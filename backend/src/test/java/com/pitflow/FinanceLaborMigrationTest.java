package com.pitflow;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.DriverManager;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

class FinanceLaborMigrationTest {
  @Test
  void v18KeepsExistingCompletedLaborCostUnknown() throws Exception {
    String url =
        System.getenv()
            .getOrDefault(
                "TEST_DB_URL",
                "jdbc:h2:mem:finance_labor_upgrade;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE");
    try (var connection =
        DriverManager.getConnection(
            url,
            System.getenv().getOrDefault("TEST_DB_USERNAME", "sa"),
            System.getenv().getOrDefault("TEST_DB_PASSWORD", ""))) {
      String original = connection.getSchema();
      String schema = "test_finance_labor_" + UUID.randomUUID().toString().replace("-", "");
      var dataSource = new SingleConnectionDataSource(connection, true);
      var db = new JdbcTemplate(dataSource);
      db.execute("CREATE SCHEMA " + schema);
      connection.setSchema(schema);
      try {
        Flyway.configure().dataSource(dataSource).defaultSchema(schema).target("17").load().migrate();
        UUID customer = UUID.randomUUID(), vehicle = UUID.randomUUID();
        UUID appointment = UUID.randomUUID(), work = UUID.randomUUID(), mechanic = UUID.randomUUID();
        db.update(
            "INSERT INTO mechanics (id,code,name,active) VALUES (?,'FIN-MIG','정비사',TRUE)",
            mechanic);
        db.update(
            "INSERT INTO users VALUES (?,'finance-migration@example.com','hash','고객','CUSTOMER',CURRENT_TIMESTAMP)",
            customer);
        db.update(
            "INSERT INTO vehicles VALUES (?,?,'11가1818','Test','Car',2026,1000,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
            vehicle,
            customer);
        OffsetDateTime start = OffsetDateTime.parse("2026-09-20T01:00:00Z");
        db.update(
            "INSERT INTO appointments (id,customer_id,vehicle_id,work_bay_id,plate_number,vehicle_label,starts_at,ends_at,status,notes,total_labor_price,duration_minutes,created_at,updated_at) VALUES (?,?,?,'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaa1','11가1818','Test Car',?,?,'VISITED','',0,30,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
            appointment,
            customer,
            vehicle,
            start,
            start.plusMinutes(30));
        db.update(
            "INSERT INTO work_orders (id,appointment_id,customer_id,vehicle_id,vehicle_label,plate_number,received_mileage,mechanic_id,mechanic_name,status,notes,received_at,updated_at,completed_at) VALUES (?,?,?,?,'Test Car','11가1818',1000,NULL,NULL,'COMPLETED','',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
            work,
            appointment,
            customer,
            vehicle);
        var latest = Flyway.configure().dataSource(dataSource).defaultSchema(schema).target("18").load();
        assertThat(latest.migrate().migrationsExecuted).isEqualTo(1);
        var snapshot = db.queryForMap("SELECT * FROM work_orders WHERE id=?", work);
        assertThat(snapshot.get("labor_cost_known")).isEqualTo(false);
        assertThat(snapshot.get("labor_cost_snapshot")).isNull();
        assertThat(snapshot.get("labor_hourly_cost_snapshot")).isNull();
        assertThat(
                db.queryForObject(
                    "SELECT hourly_cost FROM mechanics WHERE id=?", Object.class, mechanic))
            .isNull();
        assertThat(latest.migrate().migrationsExecuted).isZero();
        latest.validate();
      } finally {
        connection.setSchema(original);
        db.execute("DROP SCHEMA " + schema + " CASCADE");
      }
    }
  }
}
