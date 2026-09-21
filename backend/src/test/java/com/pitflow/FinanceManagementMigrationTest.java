package com.pitflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.DriverManager;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

class FinanceManagementMigrationTest {
  @Test
  void v20PreservesFinanceHistoryWithoutInventingSalary() throws Exception {
    String url =
        System.getenv()
            .getOrDefault(
                "TEST_DB_URL",
                "jdbc:h2:mem:finance_management_upgrade;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE");
    try (var connection =
        DriverManager.getConnection(
            url,
            System.getenv().getOrDefault("TEST_DB_USERNAME", "sa"),
            System.getenv().getOrDefault("TEST_DB_PASSWORD", ""))) {
      String original = connection.getSchema();
      String schema = "test_finance_management_" + UUID.randomUUID().toString().replace("-", "");
      var source = new SingleConnectionDataSource(connection, true);
      var db = new JdbcTemplate(source);
      db.execute("CREATE SCHEMA " + schema);
      connection.setSchema(schema);
      try {
        Flyway.configure().dataSource(source).defaultSchema(schema).target("19").load().migrate();
        UUID customer = UUID.randomUUID(), admin = UUID.randomUUID(), vehicle = UUID.randomUUID();
        UUID mechanic = UUID.randomUUID(), appointment = UUID.randomUUID(), work = UUID.randomUUID();
        UUID part = UUID.randomUUID(), operation = UUID.randomUUID(), movement = UUID.randomUUID();
        UUID lot = UUID.randomUUID(), allocation = UUID.randomUUID(), resolution = UUID.randomUUID();
        UUID invoice = UUID.randomUUID(), paymentOperation = UUID.randomUUID(), payment = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.parse("2026-09-21T00:00:00Z");
        db.update("INSERT INTO users VALUES (?,'v20-customer@example.com','hash','고객','CUSTOMER',?)", customer, now);
        db.update("INSERT INTO users VALUES (?,'v20-admin@example.com','hash','관리자','ADMIN',?)", admin, now);
        db.update("INSERT INTO vehicles VALUES (?,?,'20가2020','Test','Car',2026,1000,?,?)", vehicle, customer, now, now);
        db.update("INSERT INTO mechanics (id,code,name,active,hourly_cost) VALUES (?,'V20','정비사',TRUE,120000)", mechanic);
        db.update("INSERT INTO appointments (id,customer_id,vehicle_id,work_bay_id,plate_number,vehicle_label,starts_at,ends_at,status,notes,total_labor_price,duration_minutes,created_at,updated_at) VALUES (?,?,?,'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaa1','20가2020','Test Car',?,?,'VISITED','',100,30,?,?)", appointment, customer, vehicle, now, now.plusMinutes(30), now, now);
        db.update("INSERT INTO work_orders (id,appointment_id,customer_id,vehicle_id,vehicle_label,plate_number,received_mileage,mechanic_id,mechanic_name,status,notes,received_at,updated_at,completed_at,labor_minutes_snapshot,labor_hourly_cost_snapshot,labor_cost_snapshot,labor_cost_known) VALUES (?,?,?,?,'Test Car','20가2020',1000,?,'정비사','COMPLETED','',?,?,?,?,120000,60000,TRUE)", work, appointment, customer, vehicle, mechanic, now, now, now, 30);
        db.update("INSERT INTO parts (id,sku,name,unit,quantity,minimum_quantity,unit_price,active,archived,description) VALUES (?,'V20-PART','부품','EA',0,0,100,TRUE,FALSE,'')", part);
        db.update("INSERT INTO stock_operations (id,actor_id,request_hash,response_body,created_at) VALUES (?,?,'use','{}',?)", operation, admin, now);
        db.update("INSERT INTO stock_movements (id,operation_id,part_id,work_order_id,original_use_id,kind,quantity,balance_after,part_name,unit,unit_price,actor_id,reason,created_at) VALUES (?,?,?,?,NULL,'USE',1,0,'부품','EA',100,?,'사용',?)", movement, operation, part, work, admin, now);
        db.update("INSERT INTO inventory_cost_lots VALUES (?,?,NULL,'OPENING',1,0,NULL,FALSE,?,?)", lot, part, now, now);
        db.update("INSERT INTO inventory_cost_allocations VALUES (?,?,?,'CONSUME',NULL,1,NULL,FALSE,?)", allocation, movement, lot, now);
        db.update("INSERT INTO work_order_cost_resolutions VALUES (?,?,10,NULL,'전표 확인',?,?)", resolution, work, admin, now);
        db.update("INSERT INTO invoices (id,work_order_id,active_work_order_id,status,source_hash,total,issued_by,issued_at) VALUES (?,?,?,'OPEN','hash',100,?,?)", invoice, work, work, admin, now);
        db.update("INSERT INTO stock_operations (id,actor_id,request_hash,response_body,created_at) VALUES (?,?,'payment','{}',?)", paymentOperation, admin, now);
        db.update("INSERT INTO payment_records VALUES (?,?,?,'PAYMENT',NULL,100,'CASH','','',?,?)", payment, invoice, paymentOperation, admin, now);

        var latest = Flyway.configure().dataSource(source).defaultSchema(schema).target("20").load();
        assertThat(latest.migrate().migrationsExecuted).isEqualTo(1);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM inventory_cost_lots", Integer.class)).isOne();
        assertThat(db.queryForObject("SELECT COUNT(*) FROM inventory_cost_allocations", Integer.class)).isOne();
        assertThat(db.queryForObject("SELECT COUNT(*) FROM work_order_cost_resolutions", Integer.class)).isOne();
        assertThat(db.queryForObject("SELECT COUNT(*) FROM invoices", Integer.class)).isOne();
        assertThat(db.queryForObject("SELECT COUNT(*) FROM payment_records", Integer.class)).isOne();
        assertThat(db.queryForObject("SELECT labor_cost_snapshot FROM work_orders WHERE id=?", java.math.BigDecimal.class, work)).isEqualByComparingTo("60000");
        assertThat(db.queryForObject("SELECT monthly_base_salary FROM mechanics WHERE id=?", Object.class, mechanic)).isNull();
        assertThat(db.queryForObject("SELECT monthly_standard_hours FROM mechanics WHERE id=?", java.math.BigDecimal.class, mechanic)).isEqualByComparingTo("209");
        assertThat(db.queryForObject("SELECT default_monthly_base_salary FROM finance_settings WHERE id=1", java.math.BigDecimal.class)).isEqualByComparingTo("3500000");
        assertThat(db.queryForObject("SELECT COUNT(*) FROM finance_entries", Integer.class)).isZero();
        assertThatThrownBy(() -> db.update("UPDATE mechanics SET monthly_base_salary=-1 WHERE id=?", mechanic)).isInstanceOf(Exception.class);
        assertThatThrownBy(() -> db.update("UPDATE mechanics SET monthly_standard_hours=0 WHERE id=?", mechanic)).isInstanceOf(Exception.class);
        assertThat(latest.migrate().migrationsExecuted).isZero();
        latest.validate();
      } finally {
        connection.setSchema(original);
        db.execute("DROP SCHEMA " + schema + " CASCADE");
      }
    }
  }
}
