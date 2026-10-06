package fr.xephi.authme.datasource;

import fr.xephi.authme.data.auth.PlayerAuth;
import fr.xephi.authme.util.EmailAddressNormalizer;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Adds and fills comparison values without modifying existing delivery addresses.
 */
final class EmailNormalizationMigration {

    private static final int BATCH_SIZE = 500;

    private EmailNormalizationMigration() {
    }

    static void migrate(Connection connection, String table, Columns columns) throws SQLException {
        if (columns.NORMALIZED_EMAIL.isBlank() || columns.NORMALIZED_EMAIL.equalsIgnoreCase(columns.EMAIL)) {
            throw new IllegalArgumentException("Normalized email must use a separate database column");
        }
        boolean columnExists = false;
        try (ResultSet metadata = connection.getMetaData().getColumns(connection.getCatalog(), null, table, null)) {
            while (metadata.next()) {
                if (columns.NORMALIZED_EMAIL.equalsIgnoreCase(metadata.getString("COLUMN_NAME"))) {
                    columnExists = true;
                    break;
                }
            }
        }
        if (!columnExists) {
            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate("ALTER TABLE " + table + " ADD COLUMN "
                    + columns.NORMALIZED_EMAIL + " VARCHAR(255)");
            }
        }
        ensureIndex(connection, table, columns.NORMALIZED_EMAIL);

        String selectSql = "SELECT " + columns.NAME + ", " + columns.EMAIL + " FROM " + table
            + " WHERE " + columns.NORMALIZED_EMAIL + " IS NULL AND " + columns.EMAIL + " IS NOT NULL"
            + " AND " + columns.EMAIL + " <> ? AND " + columns.EMAIL + " <> ''"
            + " AND " + columns.NAME + " > ? ORDER BY " + columns.NAME + " LIMIT " + BATCH_SIZE;
        String updateSql = "UPDATE " + table + " SET " + columns.NORMALIZED_EMAIL + " = ? WHERE "
            + columns.NAME + " = ? AND " + columns.EMAIL + " = ? AND " + columns.NORMALIZED_EMAIL + " IS NULL";
        try (PreparedStatement select = connection.prepareStatement(selectSql);
             PreparedStatement update = connection.prepareStatement(updateSql)) {
            select.setString(1, PlayerAuth.DB_EMAIL_DEFAULT);
            String lastName = "";
            while (true) {
                select.setString(2, lastName);
                List<EmailRow> batch = new ArrayList<>(BATCH_SIZE);
                try (ResultSet rows = select.executeQuery()) {
                    while (rows.next()) {
                        batch.add(new EmailRow(rows.getString(1), rows.getString(2)));
                    }
                }
                if (batch.isEmpty()) {
                    return;
                }
                for (EmailRow row : batch) {
                    String normalized = EmailAddressNormalizer.normalize(row.email()).orElse(null);
                    if (normalized != null) {
                        update.setString(1, normalized);
                        update.setString(2, row.name());
                        update.setString(3, row.email());
                        update.addBatch();
                    }
                }
                update.executeBatch();
                update.clearBatch();
                lastName = batch.get(batch.size() - 1).name();
            }
        }
    }

    private static void ensureIndex(Connection connection, String table, String column) throws SQLException {
        try (ResultSet indexes = connection.getMetaData().getIndexInfo(
            connection.getCatalog(), null, table, false, false)) {
            while (indexes.next()) {
                if (column.equalsIgnoreCase(indexes.getString("COLUMN_NAME"))
                    && indexes.getInt("ORDINAL_POSITION") == 1) {
                    return;
                }
            }
        }
        try (Statement statement = connection.createStatement()) {
            // SQLite preserves index names on the old table while an AuthMe table rebuild is in progress.
            String indexName = "authme_normalized_email_" + UUID.randomUUID().toString().replace("-", "");
            statement.executeUpdate("CREATE INDEX " + indexName
                + " ON " + table + " (" + column + ")");
        }
    }

    private record EmailRow(String name, String email) {
    }
}
