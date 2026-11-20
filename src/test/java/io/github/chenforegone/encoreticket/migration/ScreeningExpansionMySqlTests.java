package io.github.chenforegone.encoreticket.migration;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.OptimisticLockerInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.github.chenforegone.encoreticket.mapper.MovieMapper;
import io.github.chenforegone.encoreticket.mapper.ScreeningMapper;
import io.github.chenforegone.encoreticket.model.entity.ApiIdempotency;
import io.github.chenforegone.encoreticket.model.entity.Hall;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.support.EncodedResource;

import java.math.BigDecimal;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.UUID;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the additive screening migration against an explicitly opted-in disposable MySQL 8.4 schema.
 * Does not start the application or migrate the business database.
 *
 * @author c1342
 */
@EnabledIfSystemProperty(named = "flyway.poc.enabled", matches = "true")
class ScreeningExpansionMySqlTests {

    private DriverManagerDataSource controlDataSource;
    private DriverManagerDataSource dataSource;
    private String schema;
    private boolean schemaCreated;

    @BeforeEach
    void createIsolatedSchema() throws SQLException {
        String url = System.getProperty("flyway.poc.url", "");
        if (!url.startsWith("jdbc:mysql://")) {
            throw new IllegalArgumentException("flyway.poc.url must be a MySQL JDBC URL");
        }
        URI uri = URI.create(url.substring("jdbc:".length()));
        if (uri.getUserInfo() != null || uri.getPath() == null
                || !uri.getPath().matches("/encore_ticket_flyway_poc_[a-zA-Z0-9_]+")) {
            throw new IllegalArgumentException("Only explicitly disposable Flyway POC schemas are accepted");
        }
        String password = System.getenv("FLYWAY_POC_PASSWORD");
        if (password == null || password.isBlank()) {
            password = System.getenv("MYSQL_PASSWORD");
        }
        if (password == null || password.isBlank()) {
            throw new IllegalArgumentException("Set FLYWAY_POC_PASSWORD or MYSQL_PASSWORD");
        }
        String username = System.getProperty("flyway.poc.username", "encore_ticket");
        controlDataSource = new DriverManagerDataSource(url, username, password);
        schema = "encore_ticket_flyway_poc_screen_" + UUID.randomUUID().toString().replace("-", "");
        try (var connection = controlDataSource.getConnection(); var statement = connection.createStatement()) {
            var metadata = connection.getMetaData();
            assertEquals("MySQL", metadata.getDatabaseProductName());
            assertEquals(8, metadata.getDatabaseMajorVersion());
            assertEquals(4, metadata.getDatabaseMinorVersion());
            statement.executeUpdate("CREATE DATABASE `" + schema + "` CHARACTER SET utf8mb4");
            schemaCreated = true;
        }
        String schemaUrl = "jdbc:mysql://" + uri.getRawAuthority() + "/" + schema
                + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery());
        dataSource = new DriverManagerDataSource(schemaUrl, username, password);
    }

    @AfterEach
    void dropOnlyThisTestsSchema() throws SQLException {
        if (schemaCreated && schema.matches("encore_ticket_flyway_poc_screen_[a-f0-9]{32}")) {
            try (var connection = controlDataSource.getConnection(); var statement = connection.createStatement()) {
                statement.executeUpdate("DROP DATABASE `" + schema + "`");
            }
        }
    }

    @Test
    void expandingV4PreservesExistingScreeningsWithoutInventingMovieDurations() throws SQLException {
        migrations("24").migrate();
        insertLegacyScreening();

        Flyway flyway = migrations(null);
        assertEquals(1, flyway.migrate().migrationsExecuted);

        try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            try (var result = statement.executeQuery("SELECT * FROM movie WHERE id = 1")) {
                assertTrue(result.next());
                assertNull(result.getObject("duration_minutes"));
                assertEquals("Existing movie", result.getString("title"));
                assertEquals(7, result.getInt("version"));
            }
            try (var result = statement.executeQuery("SELECT * FROM hall WHERE id = 1")) {
                assertTrue(result.next());
                assertEquals("Hall 1", result.getString("hall_name"));
                assertEquals(0L, result.getLong("schedule_version"));
            }
            try (var result = statement.executeQuery("SELECT * FROM screening WHERE id = 1")) {
                assertTrue(result.next());
                assertNull(result.getObject("duration_minutes_snapshot"));
                assertEquals(15, result.getInt("turnaround_minutes_snapshot"));
                assertEquals(0, result.getInt("version"));
                assertEquals(new BigDecimal("0.00"), result.getBigDecimal("price"));
                assertEquals(LocalDateTime.of(2026, 10, 1, 20, 0),
                        result.getObject("start_time", LocalDateTime.class));
                assertEquals(LocalDateTime.of(2026, 10, 1, 22, 7),
                        result.getObject("end_time", LocalDateTime.class));
                assertEquals(LocalDateTime.of(2026, 10, 1, 19, 50),
                        result.getObject("sale_end_time", LocalDateTime.class));
                assertEquals(LocalDateTime.of(2026, 9, 1, 0, 0),
                        result.getObject("update_time", LocalDateTime.class));
            }
        }
        flyway.validate();
        assertEquals(0, flyway.migrate().migrationsExecuted);
    }

    @Test
    void idempotencyKeysAreUniquePerOperatorAndOperationAndRemainCaseSensitive() throws SQLException {
        migrations(null).migrate();
        insertIdempotency(1, 10, "POST:/admin/screenings:v1", "Request-A");

        SQLException duplicate = assertThrows(SQLException.class,
                () -> insertIdempotency(2, 10, "POST:/admin/screenings:v1", "Request-A"));
        assertEquals(1062, duplicate.getErrorCode());
        insertIdempotency(3, 11, "POST:/admin/screenings:v1", "Request-A");
        insertIdempotency(4, 10, "POST:/orders:v1", "Request-A");
        insertIdempotency(5, 10, "POST:/admin/screenings:v1", "request-a");

        try (var connection = dataSource.getConnection(); var statement = connection.createStatement();
                var result = statement.executeQuery("SELECT COUNT(*) FROM api_idempotency")) {
            assertTrue(result.next());
            assertEquals(4, result.getInt(1));
        }
    }

    @ParameterizedTest
    @CsvSource({
            "movie,duration_minutes,0,ck_movie_duration_minutes",
            "movie,duration_minutes,-1,ck_movie_duration_minutes",
            "hall,schedule_version,-1,ck_hall_schedule_version",
            "screening,version,-1,ck_screening_version",
            "screening,duration_minutes_snapshot,0,ck_screening_duration_minutes",
            "screening,turnaround_minutes_snapshot,-1,ck_screening_turnaround_minutes"
    })
    void rejectsInvalidExpansionValues(String table, String column, int value, String constraint)
            throws SQLException {
        migrations(null).migrate();
        insertLegacyScreening();

        SQLException exception = assertThrows(SQLException.class,
                () -> execute("UPDATE " + table + " SET " + column + " = " + value + " WHERE id = 1"));

        assertEquals(3819, exception.getErrorCode());
        assertTrue(exception.getMessage().contains(constraint));
    }

    @Test
    void mapperQueriesPreserveDurationsVersionsAndTheStoredReplayResponse() throws Exception {
        migrations(null).migrate();
        insertLegacyScreening();
        execute("UPDATE movie SET duration_minutes = 127 WHERE id = 1");
        execute("UPDATE hall SET schedule_version = 4294967300 WHERE id = 1");
        execute("UPDATE screening SET duration_minutes_snapshot = 127, version = 3, price = 35, "
                + "sale_end_time = '2026-10-01 19:45:00' WHERE id = 1");
        // 当前 Hall Mapper 包含 V29 编辑版本列；仅此当前映射用例升级完整结构。
        migrations("29").migrate();
        SqlSessionFactory factory = mapperFactory();

        insertIdempotency(1, 10, "POST:/admin/screenings:v1", "Request-A");
        try (var connection = dataSource.getConnection(); var statement = connection.createStatement();
                var rows = statement.executeQuery("SELECT request_digest,response_json FROM api_idempotency")) {
            assertTrue(rows.next());
            assertEquals("00".repeat(32), rows.getString("request_digest"));
            assertTrue(rows.getString("response_json").contains("99"));
        }
        try (var session = factory.openSession()) {
            var movie = session.getMapper(MovieMapper.class).selectMoviePage(new Page<>(1, 20),
                    null, null, null, null, null, null, "releaseDate", "asc").getRecords().getFirst();
            assertEquals(127, movie.getDurationMinutes());
            var screening = session.getMapper(ScreeningMapper.class).lockHallSchedule(1L).getFirst();
            assertEquals(127, screening.getDurationMinutesSnapshot());
            assertEquals(15, screening.getTurnaroundMinutesSnapshot());
            assertEquals(3, screening.getVersion());
            assertEquals(LocalDateTime.of(2026, 10, 1, 20, 0), screening.getStartTime());
            assertEquals(4294967300L,
                    session.getMapper(HallPersistenceMapper.class).selectById(1L).getScheduleVersion());
        }
    }

    @Test
    void expansionAddsIndexesForSchedulingExpiryAndIdempotencyCleanup() throws SQLException {
        migrations(null).migrate();
        Map<String, String> indexes = new HashMap<>();
        try (var connection = dataSource.getConnection(); var statement = connection.createStatement();
                var result = statement.executeQuery("""
                        SELECT TABLE_NAME, INDEX_NAME, GROUP_CONCAT(COLUMN_NAME ORDER BY SEQ_IN_INDEX) AS columns_in_order
                        FROM information_schema.STATISTICS
                        WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME IN ('screening', 'orders', 'api_idempotency')
                        GROUP BY TABLE_NAME, INDEX_NAME
                        """)) {
            while (result.next()) {
                indexes.put(result.getString("TABLE_NAME") + "." + result.getString("INDEX_NAME"),
                        result.getString("columns_in_order"));
            }
        }
        assertEquals("hall_id,start_time,id", indexes.get("screening.idx_screening_hall_start_id"));
        assertEquals("movie_id,status,start_time,id", indexes.get("screening.idx_screening_movie_status_start_id"));
        assertEquals("status,start_time,id", indexes.get("screening.idx_screening_status_start_id"));
        assertEquals("status,sale_start_time,id", indexes.get("screening.idx_screening_status_sale_start_id"));
        assertEquals("status,sale_end_time,id", indexes.get("screening.idx_screening_status_sale_end_id"));
        assertEquals("status,end_time,id", indexes.get("screening.idx_screening_status_end_id"));
        assertEquals("screening_id,status,expires_at,id", indexes.get("orders.idx_orders_screening_status_expiry_id"));
        assertEquals("status,expires_at,id", indexes.get("orders.idx_orders_status_expiry_id"));
        assertEquals("expires_at", indexes.get("api_idempotency.idx_api_idempotency_expires_at"));
        assertTrue(indexes.containsKey("screening.idx_screening_movie_id"));
        assertTrue(indexes.containsKey("screening.idx_screening_hall_id"));
        assertTrue(indexes.containsKey("orders.idx_orders_screening_id"));
    }

    @Test
    void optimisticScreeningUpdateRejectsTheStaleEditor() throws Exception {
        migrations(null).migrate();
        insertLegacyScreening();
        SqlSessionFactory factory = mapperFactory();
        try (var first = factory.openSession(); var second = factory.openSession()) {
            var firstMapper = first.getMapper(ScreeningMapper.class);
            var secondMapper = second.getMapper(ScreeningMapper.class);
            var firstEdit = firstMapper.selectById(1L);
            var staleEdit = secondMapper.selectById(1L);
            firstEdit.setPrice(new BigDecimal("28.00"));
            assertEquals(1, firstMapper.updateOptimistic(firstEdit, firstEdit.getVersion()));
            first.commit();

            staleEdit.setPrice(new BigDecimal("18.00"));
            assertEquals(0, secondMapper.updateOptimistic(staleEdit, staleEdit.getVersion()));
            second.rollback();
        }
        try (var session = factory.openSession()) {
            var stored = session.getMapper(ScreeningMapper.class).selectById(1L);
            assertEquals(new BigDecimal("28.00"), stored.getPrice());
            assertEquals(1, stored.getVersion());
        }
    }

    @Test
    void emptyDatabaseReplaysV1ThroughV5AndValidatesOnRestart() {
        Flyway flyway = migrations(null);
        assertEquals(9, flyway.migrate().migrationsExecuted);
        assertEquals("25", flyway.info().current().getVersion().getVersion());
        flyway.validate();
        assertEquals(0, flyway.migrate().migrationsExecuted);
    }

    @Test
    void rollingBackBusinessWriteAlsoRemovesTheIdempotencyPlaceholder() throws SQLException {
        migrations(null).migrate();
        insertLegacyScreening();
        try (var connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try (var statement = connection.createStatement()) {
                statement.executeUpdate("""
                        INSERT INTO api_idempotency (operator_id, operation, idempotency_key,
                            request_digest, expires_at)
                        VALUES (10, 'POST:/admin/screenings:v1', 'Request-A', REPEAT('00', 32), '2026-09-28')
                        """);
                statement.executeUpdate("UPDATE screening SET price = 28 WHERE id = 1");
            }
            connection.rollback();
        }
        try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            try (var rows = statement.executeQuery("SELECT COUNT(*) FROM api_idempotency")) {
                assertTrue(rows.next());
                assertEquals(0, rows.getInt(1));
            }
            try (var rows = statement.executeQuery("SELECT price FROM screening WHERE id = 1")) {
                assertTrue(rows.next());
                assertEquals(new BigDecimal("0.00"), rows.getBigDecimal(1));
            }
        }
    }

    @Test
    void cumulativeMigrationsMatchTheCanonicalDatabaseDesign() throws SQLException {
        migrations("latest").migrate();
        String designSchema = "encore_ticket_flyway_poc_design_" + UUID.randomUUID().toString().replace("-", "");
        boolean designCreated = false;
        try (var control = controlDataSource.getConnection(); var statement = control.createStatement()) {
            statement.executeUpdate("CREATE DATABASE `" + designSchema + "` CHARACTER SET utf8mb4");
            designCreated = true;
            control.setCatalog(designSchema);
            ScriptUtils.executeSqlScript(control, new EncodedResource(
                    new FileSystemResource("docs/database/schema.sql"), StandardCharsets.UTF_8));
            try (var migrated = dataSource.getConnection()) {
                assertEquals(schemaShape(control), schemaShape(migrated));
            }
        } finally {
            if (designCreated && designSchema.matches("encore_ticket_flyway_poc_design_[a-f0-9]{32}")) {
                try (var control = controlDataSource.getConnection(); var statement = control.createStatement()) {
                    statement.executeUpdate("DROP DATABASE `" + designSchema + "`");
                }
            }
        }
    }

    private Map<String, String> schemaShape(Connection connection) throws SQLException {
        Map<String, String> shape = new TreeMap<>();
        try (var statement = connection.createStatement()) {
            try (var rows = statement.executeQuery("""
                    SELECT TABLE_NAME, COLUMN_NAME, COLUMN_TYPE, IS_NULLABLE, COLUMN_DEFAULT, COLLATION_NAME
                    FROM information_schema.COLUMNS
                    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME <> 'flyway_schema_history'
                    """)) {
                while (rows.next()) {
                    shape.put("column:" + rows.getString("TABLE_NAME") + "." + rows.getString("COLUMN_NAME"),
                            rows.getString("COLUMN_TYPE") + ":" + rows.getString("IS_NULLABLE") + ":"
                                    + rows.getString("COLUMN_DEFAULT") + ":" + rows.getString("COLLATION_NAME"));
                }
            }
            try (var rows = statement.executeQuery("""
                    SELECT TABLE_NAME, INDEX_NAME, NON_UNIQUE,
                        GROUP_CONCAT(COLUMN_NAME ORDER BY SEQ_IN_INDEX) AS columns_in_order
                    FROM information_schema.STATISTICS
                    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME <> 'flyway_schema_history'
                    GROUP BY TABLE_NAME, INDEX_NAME, NON_UNIQUE
                    """)) {
                while (rows.next()) {
                    shape.put("index:" + rows.getString("TABLE_NAME") + "." + rows.getString("INDEX_NAME"),
                            rows.getString("NON_UNIQUE") + ":" + rows.getString("columns_in_order"));
                }
            }
            try (var rows = statement.executeQuery("""
                    SELECT CONSTRAINT_NAME, CHECK_CLAUSE FROM information_schema.CHECK_CONSTRAINTS
                    WHERE CONSTRAINT_SCHEMA = DATABASE()
                    """)) {
                while (rows.next()) {
                    shape.put("check:" + rows.getString("CONSTRAINT_NAME"), rows.getString("CHECK_CLAUSE"));
                }
            }
        }
        return shape;
    }

    private SqlSessionFactory mapperFactory() throws Exception {
        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        interceptor.addInnerInterceptor(new OptimisticLockerInnerInterceptor());
        configuration.addInterceptor(interceptor);
        configuration.setEnvironment(new Environment("screening-expansion-test", new JdbcTransactionFactory(), dataSource));
        for (String resource : new String[]{"mapper/MovieMapper.xml", "mapper/ScreeningMapper.xml"}) {
            try (var input = Resources.getResourceAsStream(resource)) {
                new XMLMapperBuilder(input, configuration, resource, configuration.getSqlFragments()).parse();
            }
        }
        configuration.addMapper(HallPersistenceMapper.class);
        return new MybatisSqlSessionFactoryBuilder().build(configuration);
    }

    /**
     * Test-only persistence seam; does not expose the unfinished Hall management API.
     * @author c1342
     */
    interface HallPersistenceMapper extends BaseMapper<Hall> {
    }

    /**
     * Test-only persistence seam for the new idempotency entity.
     * @author c1342
     */
    interface IdempotencyPersistenceMapper extends BaseMapper<ApiIdempotency> {
    }

    private void insertIdempotency(long id, long operatorId, String operation, String key) throws SQLException {
        String sql = "INSERT INTO api_idempotency (operator_id, operation, idempotency_key, request_digest, response_json, expires_at) VALUES (?, ?, ?, ?, ?, ?)";
        try (var connection = dataSource.getConnection(); var statement = connection.prepareStatement(sql)) {
            statement.setLong(1, operatorId);
            statement.setString(2, operation);
            statement.setString(3, key);
            statement.setString(4, "00".repeat(32));
            statement.setString(5, "{\"data\":{\"id\":\"99\"}}");
            statement.setString(6, "2026-09-28 00:00:00");
            statement.executeUpdate();
        }
    }

    private void insertLegacyScreening() throws SQLException {
        execute("""
                INSERT INTO movie (id, title, release_date, status, version, create_time, update_time)
                VALUES (1, 'Existing movie', '2026-10-01', 0, 7, '2026-09-01', '2026-09-01')
                """);
        execute("""
                INSERT INTO hall (id, hall_name, hall_no, create_time, update_time)
                VALUES (1, 'Hall 1', 'H1', '2026-09-01', '2026-09-01')
                """);
        execute("""
                INSERT INTO screening (id, movie_id, hall_id, price, start_time, end_time,
                    admission_open_time, sale_start_time, sale_end_time, status, create_time, update_time)
                VALUES (1, 1, 1, 0.00, '2026-10-01 20:00:00', '2026-10-01 22:07:00',
                    '2026-10-01 19:45:00', '2026-09-28 12:00:00', '2026-10-01 19:50:00', 0, '2026-09-01', '2026-09-01')
                """);
    }

    private Flyway migrations(String target) {
        var configuration = Flyway.configure().dataSource(dataSource).locations("classpath:db/migration")
                .createSchemas(false).cleanDisabled(true);
        // This class preserves V5 expansion fixtures; only canonical parity uses the latest schema.
        configuration.target(target == null ? "25" : target);
        return configuration.load();
    }

    private void execute(String sql) throws SQLException {
        try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            statement.executeUpdate(sql);
        }
    }
}
