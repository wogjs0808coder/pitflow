package db.migration;

import java.sql.Connection;
import java.util.Locale;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

/** Adds login accounts without rewriting or discarding legacy mechanic profiles. */
public class V12__mechanic_accounts extends BaseJavaMigration {
  @Override
  public Integer getChecksum() {
    return 2026091501;
  }

  @Override
  public void migrate(Context context) throws Exception {
    Connection connection = context.getConnection();
    var db = new JdbcTemplate(new SingleConnectionDataSource(connection, true));

    var checks =
        db.queryForList(
            connection.getMetaData().getDatabaseProductName().equals("PostgreSQL")
                ? """
                  SELECT c.conname AS constraint_name, pg_catalog.pg_get_constraintdef(c.oid) AS check_clause
                  FROM pg_catalog.pg_constraint c
                  JOIN pg_catalog.pg_class t ON t.oid=c.conrelid
                  JOIN pg_catalog.pg_namespace n ON n.oid=t.relnamespace
                  JOIN pg_catalog.pg_attribute a ON a.attrelid=t.oid AND a.attnum=ANY(c.conkey)
                  WHERE n.nspname=? AND t.relname='users'
                    AND c.contype='c' AND a.attname='role'
                  """
                : """
                  SELECT tc.constraint_name,cc.check_clause
                  FROM information_schema.table_constraints tc
                  JOIN information_schema.check_constraints cc
                    ON tc.constraint_catalog=cc.constraint_catalog
                    AND tc.constraint_schema=cc.constraint_schema
                    AND tc.constraint_name=cc.constraint_name
                  WHERE LOWER(tc.table_name)='users' AND tc.table_schema=?
                    AND tc.constraint_type='CHECK'
                  """,
            connection.getSchema());
    var roleChecks =
        checks.stream()
            .filter(c -> c.get("check_clause").toString().toLowerCase(Locale.ROOT).contains("role"))
            .map(c -> c.get("constraint_name").toString())
            .distinct()
            .toList();
    if (roleChecks.size() != 1)
      throw new IllegalStateException("Expected exactly one users role check constraint");
    db.execute(
        "ALTER TABLE users DROP CONSTRAINT \""
            + roleChecks.get(0).replace("\"", "\"\"")
            + "\"");
    db.execute(
        "ALTER TABLE users ADD CONSTRAINT users_role_v12 CHECK"
            + " (role IN ('CUSTOMER','ADMIN','MECHANIC'))");
    db.execute("ALTER TABLE mechanics ADD COLUMN user_id UUID");
    db.execute(
        "ALTER TABLE mechanics ADD CONSTRAINT mechanic_user_v12 FOREIGN KEY (user_id)"
            + " REFERENCES users(id)");
    db.execute(
        "ALTER TABLE mechanics ADD CONSTRAINT mechanic_user_unique_v12 UNIQUE (user_id)");
  }
}
