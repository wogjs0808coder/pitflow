package com.pitflow;

import static org.assertj.core.api.Assertions.*;

import java.sql.DriverManager;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

class WorkAssignmentMigrationTest {
  @Test
  void v12UpgradePreservesAssignmentsAndAllowsOnlyPairedNulls() throws Exception {
    String url =
        System.getenv()
            .getOrDefault(
                "TEST_DB_URL",
                "jdbc:h2:mem:work_assignment_upgrade;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE");
    String user = System.getenv().getOrDefault("TEST_DB_USERNAME", "sa");
    String password = System.getenv().getOrDefault("TEST_DB_PASSWORD", "");
    try (var connection = DriverManager.getConnection(url, user, password)) {
      String original = connection.getSchema();
      String schema = "test_assignment_" + UUID.randomUUID().toString().replace("-", "");
      var ds = new SingleConnectionDataSource(connection, true);
      var db = new JdbcTemplate(ds);
      db.execute("CREATE SCHEMA " + schema);
      connection.setSchema(schema);
      try {
        Flyway.configure().dataSource(ds).defaultSchema(schema).target("12").load().migrate();
        UUID customer = UUID.randomUUID();
        UUID vehicle = UUID.randomUUID();
        UUID appointment = UUID.randomUUID();
        UUID mechanic = UUID.randomUUID();
        UUID work = UUID.randomUUID();
        db.update(
            "INSERT INTO users VALUES"
                + " (?,'assignment@example.com','hash','Customer','CUSTOMER',CURRENT_TIMESTAMP)",
            customer);
        db.update(
            "INSERT INTO vehicles VALUES"
                + " (?,?,'22가2222','Test','Car',2024,1000,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
            vehicle,
            customer);
        db.update(
            "INSERT INTO mechanics (id,code,name,active) VALUES (?,'LEGACY','Legacy',TRUE)",
            mechanic);
        OffsetDateTime starts = OffsetDateTime.parse("2026-09-15T01:00:00Z");
        db.update(
            """
            INSERT INTO appointments
            (id,customer_id,vehicle_id,work_bay_id,plate_number,vehicle_label,starts_at,ends_at,
             status,notes,total_labor_price,duration_minutes,created_at,updated_at)
            VALUES (?,?,?,'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaa1','22가2222','Test Car',?,?,'VISITED','',0,30,
                    CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
            """,
            appointment,
            customer,
            vehicle,
            starts,
            starts.plusMinutes(30));
        db.update(
            """
            INSERT INTO work_orders
            (id,appointment_id,customer_id,vehicle_id,vehicle_label,plate_number,received_mileage,
             mechanic_id,mechanic_name,status,notes,received_at,updated_at)
            VALUES (?,?,?,?,'Test Car','22가2222',1000,?,'Legacy','RECEIVED','',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
            """,
            work,
            appointment,
            customer,
            vehicle,
            mechanic);

        var latest = Flyway.configure().dataSource(ds).defaultSchema(schema).target("13").load();
        assertThat(latest.migrate().migrationsExecuted).isEqualTo(1);
        latest.validate();
        assertThat(
                db.queryForObject(
                    "SELECT COUNT(*) FROM work_orders WHERE id=? AND mechanic_id=? AND mechanic_name='Legacy'",
                    Integer.class,
                    work,
                    mechanic))
            .isEqualTo(1);
        db.update(
            "UPDATE work_orders SET mechanic_id=NULL,mechanic_name=NULL WHERE id=?", work);
        assertThat(
                db.queryForObject(
                    "SELECT COUNT(*) FROM work_orders WHERE id=? AND mechanic_id IS NULL AND mechanic_name IS NULL",
                    Integer.class,
                    work))
            .isEqualTo(1);
        assertThatThrownBy(
                () -> db.update("UPDATE work_orders SET mechanic_id=? WHERE id=?", mechanic, work))
            .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThat(latest.migrate().migrationsExecuted).isZero();
      } finally {
        connection.setSchema(original);
        db.execute("DROP SCHEMA " + schema + " CASCADE");
      }
    }
  }
}
