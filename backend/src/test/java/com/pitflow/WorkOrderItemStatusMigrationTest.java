package com.pitflow;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.DriverManager;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

class WorkOrderItemStatusMigrationTest {
  @Test
  void v16MapsLegacyDoneValuesToItemStatuses() throws Exception {
    String url =
        System.getenv()
            .getOrDefault(
                "TEST_DB_URL",
                "jdbc:h2:mem:item_status_upgrade;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE");
    try (var connection =
        DriverManager.getConnection(
            url,
            System.getenv().getOrDefault("TEST_DB_USERNAME", "sa"),
            System.getenv().getOrDefault("TEST_DB_PASSWORD", ""))) {
      String original = connection.getSchema();
      String schema = "test_item_status_" + UUID.randomUUID().toString().replace("-", "");
      var ds = new SingleConnectionDataSource(connection, true);
      var db = new JdbcTemplate(ds);
      db.execute("CREATE SCHEMA " + schema);
      connection.setSchema(schema);
      try {
        Flyway.configure().dataSource(ds).defaultSchema(schema).target("15").load().migrate();
        UUID customer = UUID.randomUUID(), vehicle = UUID.randomUUID(), appointment = UUID.randomUUID();
        UUID work = UUID.randomUUID(), doneItem = UUID.randomUUID(), pendingItem = UUID.randomUUID();
        db.update("INSERT INTO users VALUES (?,'migration@example.com','hash','고객','CUSTOMER',CURRENT_TIMESTAMP)", customer);
        db.update("INSERT INTO vehicles VALUES (?,?,'11가1111','Test','Car',2024,1000,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)", vehicle, customer);
        OffsetDateTime start = OffsetDateTime.parse("2026-09-15T01:00:00Z");
        db.update("INSERT INTO appointments (id,customer_id,vehicle_id,work_bay_id,plate_number,vehicle_label,starts_at,ends_at,status,notes,total_labor_price,duration_minutes,created_at,updated_at) VALUES (?,?,?,'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaa1','11가1111','Test Car',?,?,'VISITED','',0,30,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)", appointment, customer, vehicle, start, start.plusMinutes(30));
        db.update("INSERT INTO work_orders (id,appointment_id,customer_id,vehicle_id,vehicle_label,plate_number,received_mileage,mechanic_id,mechanic_name,status,notes,received_at,updated_at) VALUES (?,?,?,'" + vehicle + "','Test Car','11가1111',1000,NULL,NULL,'RECEIVED','',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)", work, appointment, customer);
        db.update("INSERT INTO work_order_items (id,work_order_id,service_item_id,name,labor_price,duration_minutes,done) VALUES (?,?,?,'완료 정비',100,30,TRUE)", doneItem, work, UUID.fromString("11111111-1111-4111-8111-111111111111"));
        db.update("INSERT INTO work_order_items (id,work_order_id,service_item_id,name,labor_price,duration_minutes,done) VALUES (?,?,?,'대기 정비',100,30,FALSE)", pendingItem, work, UUID.fromString("22222222-2222-4222-8222-222222222222"));

        var latest = Flyway.configure().dataSource(ds).defaultSchema(schema).target("16").load();
        assertThat(latest.migrate().migrationsExecuted).isEqualTo(1);
        assertThat(db.queryForObject("SELECT status FROM work_order_items WHERE id=?", String.class, doneItem)).isEqualTo("COMPLETED");
        assertThat(db.queryForObject("SELECT status FROM work_order_items WHERE id=?", String.class, pendingItem)).isEqualTo("PENDING");
        assertThat(latest.migrate().migrationsExecuted).isZero();
      } finally {
        connection.setSchema(original);
        db.execute("DROP SCHEMA " + schema + " CASCADE");
      }
    }
  }
}
