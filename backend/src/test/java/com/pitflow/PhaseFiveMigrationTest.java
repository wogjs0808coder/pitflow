package com.pitflow;

import static org.assertj.core.api.Assertions.*;

import java.sql.DriverManager;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

class PhaseFiveMigrationTest {
  @Test
  void v23AndV24PreserveDataAndAllowMultipleProviderAttempts() throws Exception {
    String url = System.getenv().getOrDefault(
        "TEST_DB_URL", "jdbc:h2:mem:phase5c_upgrade;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE");
    try (var connection = DriverManager.getConnection(url,
        System.getenv().getOrDefault("TEST_DB_USERNAME", "sa"),
        System.getenv().getOrDefault("TEST_DB_PASSWORD", ""))) {
      String original = connection.getSchema();
      String schema = "test_phase5c_" + UUID.randomUUID().toString().replace("-", "");
      var dataSource = new SingleConnectionDataSource(connection, true);
      var db = new JdbcTemplate(dataSource);
      db.execute("CREATE SCHEMA " + schema);
      connection.setSchema(schema);
      try {
        Flyway.configure().dataSource(dataSource).defaultSchema(schema).target("22").load().migrate();
        Integer unknownBefore = db.queryForObject(
            "SELECT COUNT(*) FROM inventory_cost_lots WHERE cost_known=FALSE", Integer.class);

        var v23 = Flyway.configure().dataSource(dataSource).defaultSchema(schema).target("23").load();
        assertThat(v23.migrate().migrationsExecuted).isEqualTo(1);
        assertThat(db.queryForObject(
            "SELECT COUNT(*) FROM inventory_cost_lots WHERE cost_known=FALSE", Integer.class))
            .isEqualTo(unknownBefore);
        assertThat(db.queryForObject(
            "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=? AND table_name IN ('inventory_cost_resolutions','payment_provider_orders')",
            Integer.class, schema)).isEqualTo(2);
        assertThat(v23.migrate().migrationsExecuted).isZero();
        v23.validate();

        UUID customer = UUID.randomUUID();
        UUID vehicle = UUID.randomUUID();
        UUID appointment = UUID.randomUUID();
        UUID work = UUID.randomUUID();
        UUID invoice = UUID.randomUUID();
        UUID firstOrder = UUID.randomUUID();
        db.update(
            "INSERT INTO users (id,email,password_hash,name,role,created_at) VALUES (?,?,?,?,?,CURRENT_TIMESTAMP)",
            customer, "v24-customer@example.com", "hash", "V24 Customer", "CUSTOMER");
        db.update(
            "INSERT INTO vehicles (id,owner_id,plate_number,manufacturer,model,model_year,mileage,created_at,updated_at)"
                + " VALUES (?,?,?,?,?,?,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
            vehicle, customer, "24가2424", "Test", "Car", 2024, 1000);
        db.update(
            "INSERT INTO appointments"
                + " (id,customer_id,vehicle_id,work_bay_id,plate_number,vehicle_label,starts_at,ends_at,status,notes,total_labor_price,duration_minutes,created_at,updated_at)"
                + " VALUES (?,?,?,'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaa1',?,?,'2026-09-22T01:00:00Z','2026-09-22T01:30:00Z','VISITED','',1000,30,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
            appointment, customer, vehicle, "24가2424", "Test Car");
        db.update(
            "INSERT INTO work_orders"
                + " (id,appointment_id,customer_id,vehicle_id,vehicle_label,plate_number,received_mileage,mechanic_id,mechanic_name,status,notes,received_at,updated_at,completed_at)"
                + " VALUES (?,?,?,?,?,?,1000,NULL,NULL,'COMPLETED','',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
            work, appointment, customer, vehicle, "Test Car", "24가2424");
        db.update(
            "INSERT INTO invoices"
                + " (id,work_order_id,active_work_order_id,status,source_hash,total,issued_by,issued_at)"
                + " VALUES (?,?,?,'OPEN',?,88000,?,CURRENT_TIMESTAMP)",
            invoice, work, work, "a".repeat(64), customer);
        db.update(
            "INSERT INTO payment_provider_orders"
                + " (id,invoice_id,customer_id,provider,provider_order_id,customer_key,amount,status,created_at,updated_at)"
                + " VALUES (?,?,?,'TOSS','v24_order_a','v24_customer',88000,'READY',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
            firstOrder, invoice, customer);

        var v24 = Flyway.configure().dataSource(dataSource).defaultSchema(schema).target("24").load();
        assertThat(v24.migrate().migrationsExecuted).isEqualTo(1);
        assertThat(db.queryForObject(
            "SELECT COUNT(*) FROM payment_provider_orders WHERE id=?", Integer.class, firstOrder))
            .isOne();
        db.update(
            "INSERT INTO payment_provider_orders"
                + " (id,invoice_id,customer_id,provider,provider_order_id,customer_key,amount,status,created_at,updated_at)"
                + " VALUES (?,?,?,'TOSS','v24_order_b','v24_customer',88000,'READY',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
            UUID.randomUUID(), invoice, customer);
        assertThat(db.queryForObject(
            "SELECT COUNT(*) FROM payment_provider_orders WHERE invoice_id=?", Integer.class, invoice))
            .isEqualTo(2);
        assertThatThrownBy(
                () ->
                    db.update(
                        "INSERT INTO payment_provider_orders"
                            + " (id,invoice_id,customer_id,provider,provider_order_id,customer_key,amount,status,created_at,updated_at)"
                            + " VALUES (?,?,?,'TOSS','v24_order_b','v24_customer',88000,'READY',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
                        UUID.randomUUID(), invoice, customer))
            .isInstanceOf(Exception.class);
        assertThat(v24.migrate().migrationsExecuted).isZero();
        v24.validate();
      } finally {
        connection.setSchema(original);
        db.execute("DROP SCHEMA " + schema + " CASCADE");
      }
    }
  }
}
