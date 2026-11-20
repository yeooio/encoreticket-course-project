package io.github.chenforegone.encoreticket.migration;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises the V5 read-only preflight and formal V6 migration on disposable MySQL schemas.
 *
 * @author c1342
 */
@EnabledIfSystemProperty(named = "flyway.poc.enabled", matches = "true")
class ScreeningConstraintsMySqlTests {
    private DriverManagerDataSource control;
    private DriverManagerDataSource data;
    private String schema;
    private boolean created;

    @BeforeEach
    void createDisposableDatabase() throws SQLException {
        String url = System.getProperty("flyway.poc.url", "");
        if (!url.startsWith("jdbc:mysql://")) {
            throw new IllegalArgumentException("A disposable MySQL URL is required");
        }
        URI uri = URI.create(url.substring(5));
        if (uri.getUserInfo() != null || uri.getPath() == null
                || !uri.getPath().matches("/encore_ticket_flyway_poc_[a-zA-Z0-9_]+")) {
            throw new IllegalArgumentException("Only explicitly disposable schemas are accepted");
        }
        String password = System.getenv("FLYWAY_POC_PASSWORD");
        if (password == null || password.isBlank()) {
            throw new IllegalArgumentException("Set FLYWAY_POC_PASSWORD");
        }
        String user = System.getProperty("flyway.poc.username", "encore_ticket");
        control = new DriverManagerDataSource(url, user, password);
        schema = "encore_ticket_flyway_poc_v6_" + UUID.randomUUID().toString().replace("-", "");
        try (var connection = control.getConnection(); var statement = connection.createStatement()) {
            assertEquals("MySQL", connection.getMetaData().getDatabaseProductName());
            assertEquals(8, connection.getMetaData().getDatabaseMajorVersion());
            assertEquals(4, connection.getMetaData().getDatabaseMinorVersion());
            statement.executeUpdate("CREATE DATABASE `" + schema + "` CHARACTER SET utf8mb4");
            created = true;
        }
        data = new DriverManagerDataSource("jdbc:mysql://" + uri.getRawAuthority() + "/" + schema
                + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery()), user, password);
    }

    @AfterEach
    void dropOnlyOwnDatabase() throws SQLException {
        if (created && schema.matches("encore_ticket_flyway_poc_v6_[a-f0-9]{32}")) {
            try (var connection = control.getConnection(); var statement = connection.createStatement()) {
                statement.executeUpdate("DROP DATABASE `" + schema + "`");
            }
        }
    }

    @Test
    void preflightReportsMissingMovieDurationWithoutChangingData() throws Exception {
        seedValidScreening();
        execute("UPDATE movie SET duration_minutes = NULL WHERE id = 1");

        assertEquals(Set.of("MOVIE_DURATION:1:0"), preflight());
        assertEquals(Set.of("MOVIE_DURATION:1:0"), preflight());
        assertEquals(1, scalar("SELECT COUNT(*) FROM movie WHERE duration_minutes IS NULL"));
    }

    @Test
    void preflightListsLegacyViolationsInsteadOfRepairingThem() throws Exception {
        seedValidScreening();
        execute("UPDATE movie SET release_date = '2026-10-02' WHERE id = 1");
        execute("""
                UPDATE screening SET price = 0, duration_minutes_snapshot = NULL, hall_id = 99,
                    end_time = '2026-10-01 22:07:01', sale_end_time = '2026-10-01 19:50:00'
                WHERE id = 1
                """);
        assertEquals(Set.of("SCREENING_DURATION:1:0", "SCREENING_PRICE:1:0", "SALE_CUTOFF:1:0",
                "NON_MINUTE_DURATION:1:0", "MISSING_HALL:1:99", "BEFORE_RELEASE:1:1"), preflight());
        assertEquals(1, scalar("SELECT COUNT(*) FROM screening WHERE price = 0 AND duration_minutes_snapshot IS NULL"));
    }

    @ParameterizedTest
    @CsvSource({
            "UPDATE screening SET duration_minutes_snapshot = 126 WHERE id = 1,END_DURATION:1:0",
            "UPDATE screening SET movie_id = 99 WHERE id = 1,MISSING_MOVIE:1:99",
            "UPDATE screening SET status = 9 WHERE id = 1,SCREENING_STATUS:1:0",
            "UPDATE movie SET status = 9 WHERE id = 1,MOVIE_STATUS:1:0",
            "UPDATE hall SET status = 9 WHERE id = 1,HALL_STATUS:1:0"
    })
    void preflightReportsInvalidImportedData(String mutation, String issue) throws Exception {
        seedValidScreening();
        try (var connection = data.getConnection(); var statement = connection.createStatement()) {
            statement.execute("ALTER TABLE screening ALTER CHECK ck_screening_status NOT ENFORCED");
            statement.execute("ALTER TABLE movie ALTER CHECK ck_movie_status NOT ENFORCED");
            statement.execute("ALTER TABLE hall ALTER CHECK ck_hall_status NOT ENFORCED");
            statement.executeUpdate(mutation);
        }
        assertEquals(Set.of(issue), preflight());
    }

    @Test
    void preflightIncludesTurnaroundForEndedAndOffSaleButExcludesClosedScreenings() throws Exception {
        seedValidScreening();
        execute("UPDATE screening SET status = 4 WHERE id = 1");
        execute("""
                INSERT INTO screening (id,movie_id,hall_id,price,start_time,end_time,
                    admission_open_time,duration_minutes_snapshot,sale_start_time,sale_end_time,status,create_time,update_time)
                VALUES (2,1,1,28.00,'2026-10-01 22:21:59','2026-10-02 00:28:59','2026-10-01 22:06:59',127,
                    '2026-09-28 12:00:00','2026-10-01 22:06:59',2,'2026-09-27','2026-09-27')
                """);
        assertEquals(Set.of("HALL_OVERLAP:1:2"), preflight());
        execute("""
                UPDATE screening SET start_time = '2026-10-01 22:22:00',
                    end_time = '2026-10-02 00:29:00', sale_end_time = '2026-10-01 22:07:00' WHERE id = 2
                """);
        assertEquals(Set.of(), preflight());
        execute("""
                UPDATE screening SET status = 3, start_time = '2026-10-01 22:21:59',
                    end_time = '2026-10-02 00:28:59', sale_end_time = '2026-10-01 22:06:59' WHERE id = 2
                """);
        assertEquals(Set.of(), preflight());
    }

    @Test
    void reviewedDataUpgradesAndRejectsFutureInvalidWrites() throws Exception {
        seedValidScreening();
        // Current movie duration may differ from the historical screening snapshot.
        execute("UPDATE movie SET duration_minutes = 130 WHERE id = 1");
        assertEquals(Set.of(), preflight());
        assertEquals(1, migrations(true).migrate().migrationsExecuted);
        assertEquals(0, migrations(true).migrate().migrationsExecuted);
        migrations(true).validate();
        assertEquals(127, scalar("SELECT duration_minutes_snapshot FROM screening WHERE id = 1"));
        assertEquals(28, scalar("SELECT price FROM screening WHERE id = 1"));
        assertEquals(1048, assertThrows(SQLException.class,
                () -> execute("UPDATE movie SET duration_minutes = NULL WHERE id = 1")).getErrorCode());
        assertEquals(1048, assertThrows(SQLException.class,
                () -> execute("UPDATE screening SET duration_minutes_snapshot = NULL WHERE id = 1")).getErrorCode());
        assertEquals(3819, assertThrows(SQLException.class,
                () -> execute("UPDATE screening SET price = 0 WHERE id = 1")).getErrorCode());
        assertEquals(3819, assertThrows(SQLException.class,
                () -> execute("UPDATE screening SET end_time = '2026-10-01 22:07:01' WHERE id = 1")).getErrorCode());
        assertEquals(3819, assertThrows(SQLException.class,
                () -> execute("UPDATE screening SET sale_end_time = '2026-10-01 19:50:00' WHERE id = 1")).getErrorCode());
    }

    @ParameterizedTest
    @CsvSource({
            "UPDATE screening SET price = 0 WHERE id = 1",
            "UPDATE movie SET duration_minutes = NULL WHERE id = 1",
            "UPDATE screening SET duration_minutes_snapshot = NULL WHERE id = 1",
            "UPDATE screening SET end_time = '2026-10-01 22:08:00' WHERE id = 1",
            "UPDATE screening SET sale_end_time = '2026-10-01 19:50:00' WHERE id = 1",
            "UPDATE screening SET movie_id = 99 WHERE id = 1",
            "UPDATE screening SET hall_id = 99 WHERE id = 1"
    })
    void invalidLegacyDataIsRejectedBeforeAnyBusinessTableIsTightened(String mutation) throws Exception {
        seedValidScreening();
        execute(mutation);
        Set<String> before = preflight();
        FlywayException failure = assertThrows(FlywayException.class, () -> migrations(true).migrate());
        assertTrue(failure.getMessage().contains("ck_screening_v26_preflight"));
        assertEquals(1, scalar("""
                SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE()
                    AND table_name = 'movie' AND column_name = 'duration_minutes' AND is_nullable = 'YES'
                """));
        assertEquals(before, preflight());
        assertEquals(0, scalar("SELECT COUNT(*) FROM flyway_schema_history WHERE version = '26' AND success = 1"));
    }

    @Test
    void emptyDatabaseReplaysV1ThroughV6() {
        assertEquals(10, migrations(true).migrate().migrationsExecuted);
        migrations(true).validate();
        assertEquals(0, migrations(true).migrate().migrationsExecuted);
    }

    @Test
    void v4DataRequiresExplicitVerifiedDurationsBeforeTightening() throws Exception {
        Flyway.configure().dataSource(data).locations("classpath:db/migration").target("24")
                .createSchemas(false).cleanDisabled(true).load().migrate();
        execute("""
                INSERT INTO movie (id,title,release_date,status,create_time,update_time)
                VALUES (1,'Verified fixture','2026-10-01',0,'2026-09-27','2026-09-27')
                """);
        insertHallAndScreening(false);
        assertEquals(1, migrations(false).migrate().migrationsExecuted);
        assertEquals(Set.of("MOVIE_DURATION:1:0", "SCREENING_DURATION:1:0"), preflight());
        // Explicit fixture corrections represent externally verified facts, not an automated backfill.
        execute("UPDATE movie SET duration_minutes = 127 WHERE id = 1");
        execute("UPDATE screening SET duration_minutes_snapshot = 127 WHERE id = 1");
        assertEquals(1, migrations(true).migrate().migrationsExecuted);
        assertEquals(1, scalar("""
                SELECT COUNT(*) FROM screening WHERE price = 28.00
                    AND start_time = '2026-10-01 20:00:00' AND end_time = '2026-10-01 22:07:00'
                    AND sale_end_time = '2026-10-01 19:45:00' AND update_time = '2026-09-27'
                """));
        migrations(true).validate();
    }

    private Set<String> preflight() throws Exception {
        String sql = Files.readString(Path.of("docs/database/screening-v6-preflight.sql"));
        Set<String> issues = new HashSet<>();
        try (var connection = data.getConnection()) {
            connection.setReadOnly(true);
            connection.setAutoCommit(false);
            try (var statement = connection.createStatement(); var rows = statement.executeQuery(sql)) {
                while (rows.next()) {
                    issues.add(rows.getString("issue_code") + ":" + rows.getLong("entity_id")
                            + ":" + rows.getLong("related_id"));
                }
            } finally {
                connection.rollback();
            }
        }
        return issues;
    }

    private Flyway migrations(boolean includeConstraints) {
        return Flyway.configure().dataSource(data).createSchemas(false).cleanDisabled(true)
                .locations("classpath:db/migration").target(includeConstraints ? "26" : "25").load();
    }

    private void seedValidScreening() throws SQLException {
        migrations(false).migrate();
        execute("""
                INSERT INTO movie (id,title,release_date,duration_minutes,status,create_time,update_time)
                VALUES (1,'Verified fixture','2026-10-01',127,0,'2026-09-27','2026-09-27')
                """);
        insertHallAndScreening(true);
    }

    private void insertHallAndScreening(boolean withDuration) throws SQLException {
        execute("""
                INSERT INTO hall (id,hall_name,hall_no,create_time,update_time)
                VALUES (1,'Hall 1','H1','2026-09-27','2026-09-27')
                """);
        String screening = """
                INSERT INTO screening (id,movie_id,hall_id,price,start_time,end_time,
                    admission_open_time,duration_minutes_snapshot,sale_start_time,sale_end_time,status,create_time,update_time)
                VALUES (1,1,1,28.00,'2026-10-01 20:00:00','2026-10-01 22:07:00','2026-10-01 19:45:00',127,
                    '2026-09-28 12:00:00','2026-10-01 19:45:00',0,'2026-09-27','2026-09-27')
                """;
        if (!withDuration) {
            screening = screening.replace("duration_minutes_snapshot,", "").replace(",127,", ",");
        }
        execute(screening);
    }

    private void execute(String sql) throws SQLException {
        try (var connection = data.getConnection(); var statement = connection.createStatement()) {
            statement.executeUpdate(sql);
        }
    }

    private long scalar(String sql) throws SQLException {
        try (var connection = data.getConnection(); var statement = connection.createStatement();
                var row = statement.executeQuery(sql)) {
            assertTrue(row.next());
            return row.getLong(1);
        }
    }
}
