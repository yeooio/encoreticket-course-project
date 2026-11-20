package io.github.chenforegone.encoreticket.migration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Opt-in MySQL POC for the project's Spring Boot/Flyway integration.
 *
 * <p>Run this class only against a disposable schema, for example with
 * {@code -Dflyway.poc.enabled=true -Dflyway.poc.url=...}. Set the database
 * password through {@code FLYWAY_POC_PASSWORD} (or {@code MYSQL_PASSWORD}).
 * The normal test suite keeps Flyway disabled so an existing developer
 * database is untouched.
 *
 * @author c1342
 */
@SpringBootTest
@EnabledIfSystemProperty(named = "flyway.poc.enabled", matches = "true")
class FlywayMySqlPocTests {

    private static final String HISTORY_TABLE = "flyway_schema_history";
    private static final String BASELINE_MARKER = "flyway_poc_baseline_marker";
    private static final String INCREMENTAL_MARKER = "flyway_poc_incremental_marker";
    private static final List<String> EXPECTED_SCHEMA_TABLES = List.of(
            "hall", "movie", "order_item", "orders", "payment", "refund", "role",
            "screening", "screening_seat", "seat", "sys_user", "ticket", "ticket_verification",
            "user_role", "api_idempotency", "payment_notification", "payment_audit");

    @Autowired
    private DataSource dataSource;

    @Autowired
    private Flyway flyway;

    @DynamicPropertySource
    static void flywayProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", FlywayMySqlPocTests::safePocUrl);
        registry.add(
                "spring.datasource.username",
                () -> System.getProperty("flyway.poc.username", "encore_ticket"));
        String password = firstNonBlank(
                System.getenv("FLYWAY_POC_PASSWORD"),
                System.getenv("MYSQL_PASSWORD"));
        if (password == null) {
            throw new IllegalArgumentException(
                    "Missing Flyway POC password; set FLYWAY_POC_PASSWORD or MYSQL_PASSWORD");
        }
        // Prefer an environment variable so the secret is not exposed in
        // Maven's process arguments or build logs.
        registry.add("spring.datasource.password", () -> password);
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("encore.movie.publication-scheduler.enabled", () -> "false");
        registry.add("encore.screening.lifecycle-scheduler.enabled", () -> "false");
        registry.add("encore.order.expiration-scheduler.enabled", () -> "false");
        registry.add(
                "spring.flyway.locations",
                () -> System.getProperty("flyway.poc.locations", "classpath:db/migration-poc"));
        registry.add("spring.flyway.fail-on-missing-locations", () -> "true");
        registry.add("spring.flyway.create-schemas", () -> "false");
        registry.add("spring.flyway.clean-disabled", () -> "true");
        registry.add("spring.flyway.validate-on-migrate", () -> "true");
        registry.add(
                "spring.flyway.baseline-on-migrate",
                () -> System.getProperty("flyway.poc.baseline-on-migrate", "false"));
    }

    @Test
    void migratesAndRemainsValidAcrossASecondRun() throws Exception {
        boolean baselineMode = Boolean.parseBoolean(
                System.getProperty("flyway.poc.baseline-on-migrate", "false"));

        assertNotNull(flyway);
        assertMySql84();
        assertTrue(tableExists(HISTORY_TABLE), "Flyway history table should be created");
        if (productionMode()) {
            assertProductionSchema(baselineMode);
        } else {
            assertTrue(tableExists(INCREMENTAL_MARKER), "V2 should be applied");
            assertEquals(2, migrationRows(), "V1 and V2 should both be represented in history");

            if (baselineMode) {
                assertTrue(tableExists("hall"), "Baseline fixture must contain the existing schema");
                assertFalse(
                        tableExists(BASELINE_MARKER),
                        "A baseline-existing schema must not replay the V1 marker migration");
                assertEquals(1, historyRows("BASELINE", "1"));
                assertEquals(0, historyRows("SQL", "1"));
                assertEquals(1, historyRows("SQL", "2"));
            } else {
                assertTrue(tableExists(BASELINE_MARKER), "A clean schema should apply V1");
                assertEquals(0, historyRows("BASELINE", "1"));
                assertEquals(1, historyRows("SQL", "1"));
                assertEquals(1, historyRows("SQL", "2"));
            }
        }

        long historyBeforeSecondRun = migrationRows();
        flyway.validate();
        flyway.migrate();
        assertEquals(
                historyBeforeSecondRun,
                migrationRows(),
                "A second migrate/validate run must be idempotent");
    }

    private long migrationRows() throws SQLException {
        String sql = "SELECT COUNT(*) FROM " + HISTORY_TABLE + " WHERE success = 1";
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement();
                ResultSet resultSet = statement.executeQuery(sql)) {
            assertTrue(resultSet.next());
            return resultSet.getLong(1);
        }
    }

    private void assertMySql84() throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            DatabaseMetaData metadata = connection.getMetaData();
            assertTrue(metadata.getDatabaseProductName().toLowerCase().contains("mysql"));
            assertEquals(8, metadata.getDatabaseMajorVersion());
            assertEquals(4, metadata.getDatabaseMinorVersion());
        }
    }

    private void assertProductionSchema(boolean baselineMode) throws SQLException {
        assertEquals(13, migrationRows(), "正式迁移应有十三个历史版本记录");
        for (String table : EXPECTED_SCHEMA_TABLES) {
            assertTrue(tableExists(table), "Expected production table is missing: " + table);
        }
        assertEquals(0, foreignKeyCount(), "The canonical schema must not define database foreign keys");
        assertFalse(tableExists(BASELINE_MARKER));
        assertFalse(tableExists(INCREMENTAL_MARKER));
        if (baselineMode) {
            assertEquals(1, historyRows("BASELINE", "1"));
            assertEquals(0, historyRows("SQL", "1"));
        } else {
            assertEquals(0, historyRows("BASELINE", "1"));
            assertEquals(1, historyRows("SQL", "1"));
        }
        assertEquals(1, historyRows("SQL", "2"), "V2 role seed migration should be applied");
        for (String version : new String[]{"3", "20", "21", "22", "23", "24", "25", "26", "27", "28", "29"}) {
            assertEquals(1, historyRows("SQL", version), "正式迁移版本应执行：" + version);
        }
        assertEquals(3, roleRows(), "V2 should seed ADMIN, CHECKIN_STAFF and USER");
    }

    private static boolean productionMode() {
        return System.getProperty("flyway.poc.locations", "classpath:db/migration-poc")
                .equals("classpath:db/migration");
    }

    private long historyRows(String type, String version) throws SQLException {
        String sql = "SELECT COUNT(*) FROM " + HISTORY_TABLE + " WHERE type = ? AND version = ?";
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, type);
            statement.setString(2, version);
            try (ResultSet resultSet = statement.executeQuery()) {
                assertTrue(resultSet.next());
                return resultSet.getLong(1);
            }
        }
    }

    private long foreignKeyCount() throws SQLException {
        String sql = "SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS "
                + "WHERE CONSTRAINT_SCHEMA = DATABASE() AND CONSTRAINT_TYPE = 'FOREIGN KEY'";
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement();
                ResultSet resultSet = statement.executeQuery(sql)) {
            assertTrue(resultSet.next());
            return resultSet.getLong(1);
        }
    }

    private long roleRows() throws SQLException {
        String sql = "SELECT COUNT(*) FROM role";
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement();
                ResultSet resultSet = statement.executeQuery(sql)) {
            assertTrue(resultSet.next());
            return resultSet.getLong(1);
        }
    }

    private boolean tableExists(String tableName) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            DatabaseMetaData metadata = connection.getMetaData();
            try (ResultSet tables = metadata.getTables(
                    connection.getCatalog(),
                    null,
                    tableName,
                    new String[] {"TABLE"})) {
                return tables.next();
            }
        }
    }

    private static String requiredProperty(String name) {
        String value = System.getProperty(name);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(
                    "Missing -D" + name + "; this opt-in test must target a disposable schema");
        }
        return value;
    }

    private static String safePocUrl() {
        String url = requiredProperty("flyway.poc.url");
        if (!url.startsWith("jdbc:mysql://")) {
            throw new IllegalArgumentException("-Dflyway.poc.url must be a MySQL JDBC URL");
        }

        URI uri;
        try {
            uri = URI.create(url.substring("jdbc:".length()));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Invalid -Dflyway.poc.url", exception);
        }

        String database = uri.getPath();
        if (database == null || database.length() <= 1) {
            throw new IllegalArgumentException("-Dflyway.poc.url must include a database name");
        }
        database = database.substring(1);
        if (!database.startsWith("encore_ticket_flyway_poc_")) {
            throw new IllegalArgumentException(
                    "POC refuses non-disposable schemas; use encore_ticket_flyway_poc_*");
        }
        return url;
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }
}
