package com.pitflow;

import static org.assertj.core.api.Assertions.*;

import java.sql.DriverManager;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

class MechanicAccountMigrationTest {
  @Test
  void freshDatabaseMigratesThroughV12() throws Exception {
    withDatabase(
        "mechanic_fresh",
        (db, ds, schema) -> {
          var flyway = Flyway.configure().dataSource(ds).defaultSchema(schema).target("12").load();
          flyway.migrate();
          flyway.validate();
          assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("12");
          assertThat(
                  db.queryForObject(
                      "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=? AND"
                          + " table_name='mechanics' AND column_name='user_id'",
                      Integer.class,
                      schema))
              .isEqualTo(1);
          db.update(
              "INSERT INTO users VALUES"
                  + " (?,'fresh-mechanic@example.com','hash','Mechanic','MECHANIC',CURRENT_TIMESTAMP)",
              UUID.randomUUID());
        });
  }

  @Test
  void v11UpgradePreservesLegacyMechanicsAndAddsOptionalUniqueAccountLink() throws Exception {
    withDatabase(
        "mechanic_upgrade",
        (db, ds, schema) -> {
          Flyway.configure().dataSource(ds).defaultSchema(schema).target("11").load().migrate();
          UUID legacy = UUID.randomUUID();
          db.update(
              "INSERT INTO mechanics (id,code,name,active) VALUES (?,'LEGACY','Legacy',TRUE)",
              legacy);

          var latest = Flyway.configure().dataSource(ds).defaultSchema(schema).target("12").load();
          assertThat(latest.migrate().migrationsExecuted).isEqualTo(1);
          latest.validate();
          assertThat(
                  db.queryForObject(
                      "SELECT COUNT(*) FROM mechanics WHERE id=? AND user_id IS NULL",
                      Integer.class,
                      legacy))
              .isEqualTo(1);

          UUID account = UUID.randomUUID();
          db.update(
              "INSERT INTO users VALUES"
                  + " (?,'linked-mechanic@example.com','hash','Mechanic','MECHANIC',CURRENT_TIMESTAMP)",
              account);
          db.update("UPDATE mechanics SET user_id=? WHERE id=?", account, legacy);
          UUID duplicate = UUID.randomUUID();
          db.update(
              "INSERT INTO mechanics (id,code,name,active) VALUES (?,'DUP','Duplicate',TRUE)",
              duplicate);
          assertThatThrownBy(() -> db.update("UPDATE mechanics SET user_id=? WHERE id=?", account, duplicate))
              .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
          assertThat(latest.migrate().migrationsExecuted).isZero();
        });
  }

  private void withDatabase(String name, MigrationAssertion assertion) throws Exception {
    String url =
        System.getenv()
            .getOrDefault(
                "TEST_DB_URL",
                "jdbc:h2:mem:" + name + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE");
    String user = System.getenv().getOrDefault("TEST_DB_USERNAME", "sa");
    String password = System.getenv().getOrDefault("TEST_DB_PASSWORD", "");
    try (var connection = DriverManager.getConnection(url, user, password)) {
      String original = connection.getSchema();
      String schema = "test_" + name + "_" + UUID.randomUUID().toString().replace("-", "");
      var ds = new SingleConnectionDataSource(connection, true);
      var db = new JdbcTemplate(ds);
      db.execute("CREATE SCHEMA " + schema);
      connection.setSchema(schema);
      try {
        assertion.run(db, ds, schema);
      } finally {
        connection.setSchema(original);
        db.execute("DROP SCHEMA " + schema + " CASCADE");
      }
    }
  }

  @FunctionalInterface
  private interface MigrationAssertion {
    void run(JdbcTemplate db, SingleConnectionDataSource ds, String schema) throws Exception;
  }
}
