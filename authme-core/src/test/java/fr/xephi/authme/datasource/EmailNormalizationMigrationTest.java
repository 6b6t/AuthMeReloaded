package fr.xephi.authme.datasource;

import fr.xephi.authme.TestHelper;
import fr.xephi.authme.settings.Settings;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

class EmailNormalizationMigrationTest {

    @Test
    void backfillsLegacyAliasesInBatchesWithoutChangingDeliveryAddresses() throws Exception {
        Settings settings = mock(Settings.class);
        TestHelper.returnDefaultsForAllProperties(settings);
        Columns columns = new Columns(settings);
        int accounts = 1007;
        String delivery = "Player.Name+game@GOOGLEMAIL.COM";
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite::memory:")) {
            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate("CREATE TABLE authme (username VARCHAR(255) PRIMARY KEY, email VARCHAR(255))");
                statement.executeUpdate("INSERT INTO authme VALUES ('invalid', '+game@gmail.com'),"
                    + " ('no-email', NULL), ('default-email', 'your@email.com')");
            }
            try (PreparedStatement insert = connection.prepareStatement("INSERT INTO authme VALUES (?, ?)")) {
                for (int i = 0; i < accounts; i++) {
                    insert.setString(1, String.format(Locale.ROOT, "legacy-%04d", i));
                    insert.setString(2, delivery);
                    insert.addBatch();
                }
                insert.executeBatch();
            }

            EmailNormalizationMigration.migrate(connection, "authme", columns);
            EmailNormalizationMigration.migrate(connection, "authme", columns);

            try (PreparedStatement select = connection.prepareStatement(
                "SELECT COUNT(*) FROM authme WHERE email = ? AND normalizedEmail = ?")) {
                select.setString(1, delivery);
                select.setString(2, "playername@gmail.com");
                try (ResultSet rows = select.executeQuery()) {
                    rows.next();
                    assertEquals(accounts, rows.getInt(1));
                }
            }
            try (Statement statement = connection.createStatement();
                 ResultSet rows = statement.executeQuery("SELECT COUNT(*) FROM authme WHERE normalizedEmail IS NULL")) {
                rows.next();
                assertEquals(3, rows.getInt(1));
            }
        }
    }
}
