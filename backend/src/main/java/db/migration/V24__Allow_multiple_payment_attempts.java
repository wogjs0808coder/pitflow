package db.migration;

import java.sql.SQLException;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

public class V24__Allow_multiple_payment_attempts extends BaseJavaMigration {
  @Override
  public void migrate(Context context) throws SQLException {
    String constraint = null;
    String sql =
        """
        SELECT tc.constraint_name
        FROM information_schema.table_constraints tc
        JOIN information_schema.key_column_usage kcu
          ON tc.constraint_catalog=kcu.constraint_catalog
         AND tc.constraint_schema=kcu.constraint_schema
         AND tc.constraint_name=kcu.constraint_name
         AND tc.table_catalog=kcu.table_catalog
         AND tc.table_schema=kcu.table_schema
         AND tc.table_name=kcu.table_name
        WHERE tc.table_schema=CURRENT_SCHEMA
          AND tc.table_name='payment_provider_orders'
          AND tc.constraint_type='UNIQUE'
        GROUP BY tc.constraint_name
        HAVING COUNT(*)=1 AND MIN(kcu.column_name)='invoice_id'
        """;
    try (var query = context.getConnection().prepareStatement(sql);
        var rows = query.executeQuery()) {
      if (rows.next()) constraint = rows.getString(1);
      if (rows.next()) throw new SQLException("Multiple invoice-only unique constraints found");
    }
    if (constraint == null) {
      throw new SQLException("Invoice unique constraint not found");
    }
    String foreignKey = null;
    String foreignKeySql =
        """
        SELECT tc.constraint_name
        FROM information_schema.table_constraints tc
        JOIN information_schema.key_column_usage kcu
          ON tc.constraint_catalog=kcu.constraint_catalog
         AND tc.constraint_schema=kcu.constraint_schema
         AND tc.constraint_name=kcu.constraint_name
         AND tc.table_catalog=kcu.table_catalog
         AND tc.table_schema=kcu.table_schema
         AND tc.table_name=kcu.table_name
        WHERE tc.table_schema=CURRENT_SCHEMA
          AND tc.table_name='payment_provider_orders'
          AND tc.constraint_type='FOREIGN KEY'
        GROUP BY tc.constraint_name
        HAVING COUNT(*)=1 AND MIN(kcu.column_name)='invoice_id'
        """;
    try (var query = context.getConnection().prepareStatement(foreignKeySql);
        var rows = query.executeQuery()) {
      if (rows.next()) foreignKey = rows.getString(1);
      if (rows.next()) throw new SQLException("Multiple invoice foreign keys found");
    }
    if (foreignKey == null) {
      throw new SQLException("Invoice foreign key not found");
    }
    String quote = context.getConnection().getMetaData().getIdentifierQuoteString().trim();
    String identifier = quote + constraint.replace(quote, quote + quote) + quote;
    String foreignKeyIdentifier = quote + foreignKey.replace(quote, quote + quote) + quote;
    try (var drop = context.getConnection().createStatement()) {
      drop.execute("ALTER TABLE payment_provider_orders DROP CONSTRAINT " + foreignKeyIdentifier);
      drop.execute("ALTER TABLE payment_provider_orders DROP CONSTRAINT " + identifier);
      drop.execute(
          "ALTER TABLE payment_provider_orders ADD CONSTRAINT"
              + " fk_payment_provider_orders_invoice FOREIGN KEY (invoice_id) REFERENCES invoices(id)");
    }
  }
}
