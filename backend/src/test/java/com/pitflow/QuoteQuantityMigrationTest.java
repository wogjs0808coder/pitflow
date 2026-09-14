package com.pitflow;

import static org.assertj.core.api.Assertions.*;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.sql.DriverManager;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

class QuoteQuantityMigrationTest {
  @Test
  void upgradesV8KeepingLegacyQuantitiesAndWithoutBackfillingPartQuotes() throws Exception {
    String url =
        System.getenv()
            .getOrDefault(
                "TEST_DB_URL",
                "jdbc:h2:mem:quote_quantity_upgrade;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE");
    try (var connection =
        DriverManager.getConnection(
            url,
            System.getenv().getOrDefault("TEST_DB_USERNAME", "sa"),
            System.getenv().getOrDefault("TEST_DB_PASSWORD", ""))) {
      String original = connection.getSchema(),
          schema = "test_quote_quantity_" + UUID.randomUUID().toString().replace("-", "");
      var ds = new SingleConnectionDataSource(connection, true);
      var db = new JdbcTemplate(ds);
      db.execute("CREATE SCHEMA " + schema);
      connection.setSchema(schema);
      try {
        Flyway.configure().dataSource(ds).defaultSchema(schema).target("8").load().migrate();

        UUID customer = UUID.randomUUID();
        UUID vehicle = UUID.randomUUID();
        UUID appointment = UUID.randomUUID();
        UUID work = UUID.randomUUID();
        UUID mechanic = UUID.randomUUID();
        UUID service =
            db.queryForObject(
                "SELECT id FROM service_items ORDER BY name FETCH FIRST 1 ROW ONLY", UUID.class);
        UUID bay =
            db.queryForObject("SELECT id FROM work_bays ORDER BY id FETCH FIRST 1 ROW ONLY", UUID.class);

        db.update(
            "INSERT INTO users (id,email,password_hash,name,role,created_at) VALUES"
                + " (?,'legacy@example.com','x','Legacy','CUSTOMER',CURRENT_TIMESTAMP)",
            customer);
        db.update(
            "INSERT INTO vehicles"
                + " (id,owner_id,plate_number,manufacturer,model,model_year,mileage,created_at,updated_at)"
                + " VALUES (?,?,'12가3456','Test','Car',2024,1000,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
            vehicle,
            customer);
        var startsAt = OffsetDateTime.now(ZoneOffset.UTC).withNano(0);
        var endsAt = startsAt.plusMinutes(30);
        db.update(
            """
            INSERT INTO appointments
            (id,customer_id,vehicle_id,work_bay_id,plate_number,vehicle_label,
            starts_at,ends_at,status,notes,total_labor_price,duration_minutes,
            created_at,updated_at)
            VALUES
            (?,?,?,?,'12가3456','Test Car',?,?,'PENDING','',10000,30,
            CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
            """,
            appointment,
            customer,
            vehicle,
            bay,
            startsAt,
            endsAt);
        db.update(
            "INSERT INTO appointment_items"
                + " (appointment_id,service_item_id,name,labor_price,duration_minutes) VALUES"
                + " (?,?, 'Legacy Service',10000,30)",
            appointment,
            service);
        db.update(
            "INSERT INTO mechanics (id,code,name,active) VALUES (?,'M1','Legacy Mechanic',TRUE)",
            mechanic);
        db.update(
            "INSERT INTO work_orders"
                + " (id,appointment_id,customer_id,vehicle_id,vehicle_label,plate_number,received_mileage,mechanic_id,mechanic_name,status,notes,received_at,updated_at)"
                + " VALUES (?,?,?,?,'Test Car','12가3456',1000,?,'Legacy Mechanic','RECEIVED','',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
            work,
            appointment,
            customer,
            vehicle,
            mechanic);
        db.update(
            "INSERT INTO work_order_items"
                + " (id,work_order_id,service_item_id,name,labor_price,duration_minutes) VALUES"
                + " (?,?,?,'Legacy Service',10000,30)",
            UUID.randomUUID(),
            work,
            service);

        var latest = Flyway.configure().dataSource(ds).defaultSchema(schema).target("9").load();
        assertThat(latest.migrate().migrationsExecuted).isEqualTo(1);
        latest.validate();

        assertThat(
                db.queryForObject(
                    "SELECT quantity FROM appointment_items WHERE appointment_id=? AND service_item_id=?",
                    Integer.class,
                    appointment,
                    service))
            .isEqualTo(1);
        assertThat(
                db.queryForObject(
                    "SELECT parts_quote_captured FROM appointment_items WHERE appointment_id=? AND service_item_id=?",
                    Boolean.class,
                    appointment,
                    service))
            .isFalse();
        assertThat(
                db.queryForObject(
                    "SELECT COUNT(*) FROM appointment_item_parts WHERE appointment_id=?",
                    Integer.class,
                    appointment))
            .isZero();
        assertThat(
                db.queryForObject(
                    "SELECT quantity FROM work_order_items WHERE work_order_id=?",
                    Integer.class,
                    work))
            .isEqualTo(1);
        assertThat(
                db.queryForObject(
                    "SELECT COUNT(*) FROM service_part_requirements WHERE quantity_confirmed=TRUE",
                    Integer.class))
            .isZero();
        assertThat(latest.migrate().migrationsExecuted).isZero();
      } finally {
        connection.setSchema(original);
        db.execute("DROP SCHEMA " + schema + " CASCADE");
      }
    }
  }
}
