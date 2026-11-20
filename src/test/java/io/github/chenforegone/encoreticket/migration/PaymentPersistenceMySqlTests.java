package io.github.chenforegone.encoreticket.migration;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.github.chenforegone.encoreticket.mapper.PaymentIdempotencyMapper;
import io.github.chenforegone.encoreticket.scheduler.PaymentIdempotencyCleanup;
import java.net.URI;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 显式启用的支付 MySQL 8.4 持久化测试，只接受空的专用临时库。
 * 密码经 FLYWAY_POC_PASSWORD 或 MYSQL_PASSWORD 提供，不读取业务配置。
 *
 * @author c1342
 */
@EnabledIfSystemProperty(named = "flyway.poc.enabled", matches = "true")
class PaymentPersistenceMySqlTests {
    private static final Instant NOW = Instant.parse("2026-10-19T12:00:00Z");
    private static DriverManagerDataSource dataSource;
    private static JdbcTemplate jdbc;
    private static Flyway flyway;
    private static String unavailableReason;

    @BeforeAll
    static void prepareDisposableSchema() throws Exception {
        String url = System.getProperty("flyway.poc.url", "");
        assertTrue(url.startsWith("jdbc:mysql://"), "必须显式指定 MySQL JDBC URL");
        URI uri = URI.create(url.substring(5));
        assertTrue(uri.getUserInfo() == null && uri.getPath() != null
                && uri.getPath().matches("/encore_ticket_flyway_poc_payment_[A-Za-z0-9_]+"),
                "仅允许 encore_ticket_flyway_poc_payment_* 专用库，禁止 URL 内嵌凭据");
        String password = System.getenv("FLYWAY_POC_PASSWORD");
        if (password == null || password.isBlank()) password = System.getenv("MYSQL_PASSWORD");
        if (password == null || password.isBlank()) {
            unavailableReason = "SKIP：未配置隔离 MySQL 测试密码";
            return;
        }
        dataSource = new DriverManagerDataSource(url,
                System.getProperty("flyway.poc.username", "encore_ticket"), password);
        // 只在连通性探测阶段跳过；迁移、权限、SQL 和断言失败必须真实失败。
        try (Connection connection = DriverManager.getConnection(url,
                System.getProperty("flyway.poc.username", "encore_ticket"), password)) {
            assertEquals("MySQL", connection.getMetaData().getDatabaseProductName());
            assertEquals(8, connection.getMetaData().getDatabaseMajorVersion());
            assertEquals(4, connection.getMetaData().getDatabaseMinorVersion());
        } catch (SQLException exception) {
            if (exception.getSQLState() != null && exception.getSQLState().startsWith("08")) {
                unavailableReason = "SKIP：隔离 MySQL 不可连接，SQLState=" + exception.getSQLState();
                return;
            }
            throw exception;
        }
        jdbc = new JdbcTemplate(dataSource);
        assertEquals(0L, jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables "
                + "WHERE table_schema=DATABASE()", Long.class), "拒绝清空或复用非空数据库，请提供新的临时库");
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration")
                .cleanDisabled(true).target("2").load().migrate();
        jdbc.update("INSERT INTO payment(id,payment_no,order_id,amount,status,create_time,update_time) "
                + "VALUES(900,'legacy-payment',900,50,2,UTC_TIMESTAMP(),UTC_TIMESTAMP())");
        flyway = Flyway.configure().dataSource(dataSource).locations("classpath:db/migration")
                .cleanDisabled(true).load();
        flyway.migrate();
        assertEquals("legacy-900", jdbc.queryForObject("SELECT attempt_id FROM payment WHERE id=900", String.class));
        assertEquals("CNY", jdbc.queryForObject("SELECT currency FROM payment WHERE id=900", String.class));
    }

    @BeforeEach
    void resetOwnedFixture() {
        assumeTrue(unavailableReason == null, unavailableReason);
        for (String table : List.of("payment_notification", "payment_audit", "api_idempotency", "ticket", "payment", "orders")) {
            jdbc.update("DELETE FROM " + table);
        }
    }

    @Test
    void migrationsValidateAndSecondRunDoesNotReapply() {
        flyway.validate();
        assertEquals(0, flyway.migrate().migrationsExecuted);
        assertEquals((long) flyway.info().all().length,
                jdbc.queryForObject("SELECT COUNT(*) FROM flyway_schema_history WHERE success=1", Long.class));
    }

    @Test
    void freshCanonicalSchemaMatchesMigrationStructure() throws Exception {
        // 在同一专用库内使用独立连接的临时表名，不清理或重建迁移表。
        String canonical = java.nio.file.Files.readString(java.nio.file.Path.of("docs/database/schema.sql"));
        for (String table : List.of("orders", "payment", "payment_notification", "payment_audit", "api_idempotency")) {
            String start = "CREATE TABLE `" + table + "` (";
            int from = canonical.indexOf(start);
            int to = canonical.indexOf(';', from);
            assertTrue(from >= 0 && to > from);
            String sql = canonical.substring(from, to).replaceFirst("CREATE TABLE `" + table + "`",
                    "CREATE TEMPORARY TABLE `canonical_" + table + "`");
            try (Connection connection = dataSource.getConnection(); var statement = connection.createStatement()) {
                statement.execute(sql);
                var expected = columns(statement, "canonical_" + table);
                var actual = columns(statement, table);
                assertEquals(expected, actual, table + " 字段结构应与累计迁移一致");
            }
        }
    }

    @Test
    void dirtyHistoricalDoubleSuccessBlocksV3WithoutDeletingHistory() throws Exception {
        String baseline = java.nio.file.Files.readString(java.nio.file.Path.of(
                "src/main/resources/db/migration/V1__baseline_schema.sql"));
        String schema = "encore_ticket_flyway_poc_hist_"
                + java.util.UUID.randomUUID().toString().replace("-", "");
        try (Connection connection = dataSource.getConnection()) {
            String originalCatalog = connection.getCatalog();
            // CHECK 名在 schema 内唯一，必须与已完成 V3 的累计迁移库隔离。
            try (var statement = connection.createStatement()) {
                statement.executeUpdate("CREATE DATABASE `" + schema + "` CHARACTER SET utf8mb4");
            }
            try {
                connection.setCatalog(schema);
                try (var statement = connection.createStatement()) {
                    for (String table : List.of("orders", "payment")) {
                        int from = baseline.indexOf("CREATE TABLE `" + table + "` (");
                        int to = baseline.indexOf(';', from);
                        statement.execute(baseline.substring(from, to));
                    }
                    statement.executeUpdate("INSERT INTO payment(id,payment_no,order_id,amount,status,create_time,update_time) "
                            + "VALUES(1,'dirty-1',1,50,1,UTC_TIMESTAMP(),UTC_TIMESTAMP()),"
                            + "(2,'dirty-2',1,50,1,UTC_TIMESTAMP(),UTC_TIMESTAMP())");
                    var failure = assertThrows(org.springframework.jdbc.datasource.init.ScriptException.class,
                            () -> org.springframework.jdbc.datasource.init.ScriptUtils.executeSqlScript(connection,
                                    new org.springframework.core.io.ClassPathResource("db/migration/V3__payment_support.sql")));
                    SQLException sqlFailure = assertInstanceOf(SQLException.class, failure.getCause());
                    assertEquals(1062, sqlFailure.getErrorCode(),
                            "必须因重复成功唯一约束失败，不能把其他 SQL 错误当作预期结果");
                    assertEquals("23000", sqlFailure.getSQLState());
                    assertTrue(sqlFailure.getMessage().contains("uk_payment_successful_order_id"));
                    try (var rows = statement.executeQuery(
                            "SELECT id,payment_no,order_id,amount,status FROM payment ORDER BY id")) {
                        for (int id = 1; id <= 2; id++) {
                            assertTrue(rows.next());
                            assertEquals(id, rows.getLong("id"));
                            assertEquals("dirty-" + id, rows.getString("payment_no"));
                            assertEquals(1L, rows.getLong("order_id"));
                            assertEquals(0, new java.math.BigDecimal("50").compareTo(rows.getBigDecimal("amount")));
                            assertEquals(1, rows.getInt("status"));
                        }
                        assertFalse(rows.next(), "失败迁移不得删除或新增历史支付");
                    }
                }
            } finally {
                connection.setCatalog(originalCatalog);
                try (var statement = connection.createStatement()) {
                    statement.executeUpdate("DROP DATABASE `" + schema + "`");
                }
            }
        }
    }

    @Test
    void requiresAttemptAtFirstInsertAndRejectsSecondSuccess() {
        var missingAttempt = assertThrows(org.springframework.jdbc.UncategorizedSQLException.class, () -> jdbc.update(
                "INSERT INTO payment(id,payment_no,order_id,amount,status,create_time,update_time) "
                        + "VALUES(1,'missing-attempt',1,50,0,UTC_TIMESTAMP(),UTC_TIMESTAMP())"));
        assertEquals(1364, missingAttempt.getSQLException().getErrorCode());
        assertEquals("HY000", missingAttempt.getSQLException().getSQLState());
        assertTrue(missingAttempt.getSQLException().getMessage().contains("attempt_id"));
        assertEquals(0L, count("payment"));
        payment(1, 0);
        payment(2, 2);
        assertEquals(1, jdbc.update("UPDATE payment SET status=1 WHERE id=1"));
        assertThrows(DataIntegrityViolationException.class,
                () -> jdbc.update("UPDATE payment SET status=1 WHERE id=2"));
        assertEquals(2, jdbc.queryForObject("SELECT status FROM payment WHERE id=2", Integer.class));
        assertThrows(DataIntegrityViolationException.class, () -> payment(3, 1));
    }

    @Test
    void enforcesTicketNotificationAndIdempotencyUniqueness() {
        jdbc.update("INSERT INTO ticket VALUES(1,0,1,'ticket-1',UTC_TIMESTAMP(),UTC_TIMESTAMP())");
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(
                "INSERT INTO ticket VALUES(2,0,1,'ticket-2',UTC_TIMESTAMP(),UTC_TIMESTAMP())"));
        notification(1, "Event");
        assertThrows(DataIntegrityViolationException.class, () -> notification(2, "Event"));
        notification(3, "event");
        PaymentIdempotencyMapper mapper = new PaymentIdempotencyMapper(jdbc);
        mapper.reserve(1, "payment-create:v1", "Key", "a".repeat(64), NOW);
        mapper.reserve(1, "payment-create:v1", "Key", "b".repeat(64), NOW);
        mapper.reserve(1, "payment-create:v1", "key", "a".repeat(64), NOW);
        assertEquals("a".repeat(64), mapper.find(1, "payment-create:v1", "Key", false).digest());
        assertEquals(2L, count("api_idempotency"));
    }

    @Test
    void rollsBackBusinessAuditNotificationAndResponseTogether() {
        PaymentIdempotencyMapper mapper = new PaymentIdempotencyMapper(jdbc);
        TransactionTemplate transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        assertThrows(IllegalStateException.class, () -> transaction.executeWithoutResult(status -> {
            payment(1, 1);
            jdbc.update("INSERT INTO payment_audit(id,payment_id,order_id,operation,source,create_time) "
                    + "VALUES(1,1,1,'CHANNEL_SUCCESS','CHANNEL',UTC_TIMESTAMP())");
            notification(1, "atomic-event");
            mapper.reserve(1, "payment-create:v1", "atomic", "a".repeat(64), NOW);
            assertEquals(1, mapper.save(1, "payment-create:v1", "atomic", "{\"paymentId\":\"1\"}", NOW));
            throw new IllegalStateException("故障注入");
        }));
        for (String table : List.of("payment", "payment_audit", "payment_notification", "api_idempotency")) {
            assertEquals(0L, count(table), table + " 必须回滚");
        }
    }

    @Test
    void concurrentSuccessAllowsExactlyOneWinner() throws Exception {
        payment(1, 0);
        payment(2, 0);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> succeed(1, ready, start));
            var second = executor.submit(() -> succeed(2, ready, start));
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            assertEquals(1, first.get(20, TimeUnit.SECONDS) + second.get(20, TimeUnit.SECONDS));
        }
        assertEquals(1L, jdbc.queryForObject("SELECT COUNT(*) FROM payment WHERE status=1", Long.class));
    }

    @Test
    void concurrentIdempotencyReservationKeepsOneResponse() throws Exception {
        PaymentIdempotencyMapper mapper = new PaymentIdempotencyMapper(jdbc);
        CountDownLatch start = new CountDownLatch(1);
        java.util.concurrent.Callable<String> request = () -> {
            assertTrue(start.await(10, TimeUnit.SECONDS));
            return new TransactionTemplate(new DataSourceTransactionManager(dataSource)).execute(status -> {
                mapper.reserve(1, "payment-create:v1", "concurrent", "a".repeat(64), NOW);
                var snapshot = mapper.find(1, "payment-create:v1", "concurrent", true);
                if (snapshot.responseJson() == null) {
                    assertEquals(1, mapper.save(1, "payment-create:v1", "concurrent", "{\"paymentId\":\"1\"}", NOW));
                }
                return mapper.find(1, "payment-create:v1", "concurrent", true).responseJson();
            });
        };
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(request);
            var second = executor.submit(request);
            start.countDown();
            String response = first.get(20, TimeUnit.SECONDS);
            assertNotNull(response);
            assertEquals(response, second.get(20, TimeUnit.SECONDS));
        }
        assertEquals(1L, count("api_idempotency"));
    }

    @Test
    void optimisticOrderStateAllowsOnlyPaymentOrExpiration() throws Exception {
        jdbc.update("INSERT INTO orders(id,order_no,user_id,screening_id,total_amount,expires_at,status,version,create_time,update_time) "
                + "VALUES(1,'order-1',1,1,50,UTC_TIMESTAMP(),0,0,UTC_TIMESTAMP(),UTC_TIMESTAMP())");
        try (var executor = Executors.newFixedThreadPool(2)) {
            CountDownLatch start = new CountDownLatch(1);
            var paid = executor.submit(() -> { start.await(); return changeOrder(1); });
            var expired = executor.submit(() -> { start.await(); return changeOrder(3); });
            start.countDown();
            assertEquals(1, paid.get(20, TimeUnit.SECONDS) + expired.get(20, TimeUnit.SECONDS));
        }
        assertEquals(1, jdbc.queryForObject("SELECT version FROM orders WHERE id=1", Integer.class));
        assertEquals(0, changeOrder(1));
    }

    @Test
    void cleanupIsBoundedAndKeepsOtherOperationsAndFutureSnapshots() {
        for (int i = 0; i < 501; i++) snapshot(i, "payment-create:v1", NOW.minusSeconds(1));
        snapshot(1000, "payment-admin-retry:v1", NOW);
        snapshot(1001, "payment-create:v1", NOW.plusSeconds(1));
        snapshot(1002, "ticket-verification:v1", NOW.minusSeconds(1));
        PaymentIdempotencyCleanup cleanup = new PaymentIdempotencyCleanup(jdbc, () -> NOW);
        cleanup.cleanup();
        assertEquals(4L, count("api_idempotency"));
        cleanup.cleanup();
        assertEquals(2L, count("api_idempotency"));
        assertEquals(1L, jdbc.queryForObject("SELECT COUNT(*) FROM api_idempotency WHERE operator_id=1001", Long.class));
        assertEquals(1L, jdbc.queryForObject("SELECT COUNT(*) FROM api_idempotency WHERE operator_id=1002", Long.class));
    }

    private static List<String> columns(java.sql.Statement statement, String table) throws SQLException {
        var values = new java.util.ArrayList<String>();
        try (var rows = statement.executeQuery("SHOW FULL COLUMNS FROM `" + table + "`")) {
            while (rows.next()) {
                String extra = rows.getString("Extra");
                // MySQL 临时表用字符串 NULL 表示无附加属性；保留 STORED GENERATED 等真实属性。
                if (table.startsWith("canonical_") && "NULL".equals(extra)) extra = "";
                values.add(rows.getString("Field") + ":" + rows.getString("Type")
                        + ":" + rows.getString("Null") + ":" + rows.getString("Default")
                        + ":" + extra + ":" + rows.getString("Collation"));
            }
        }
        values.sort(String::compareTo);
        return values;
    }

    private static void payment(long id, int status) {
        jdbc.update("INSERT INTO payment(id,payment_no,order_id,amount,status,attempt_id,create_time,update_time) "
                + "VALUES(?,?,1,50,?,?,UTC_TIMESTAMP(),UTC_TIMESTAMP())", id, "payment-" + id, status, "attempt-" + id);
    }

    private static void notification(long id, String event) {
        jdbc.update("INSERT INTO payment_notification(id,provider,event_id,payment_no,event_type,payload_hash,processed_at,create_time,attempt_id) "
                + "VALUES(?,'SIMULATED',?,'payment-1','SUCCESS',UNHEX(REPEAT('ab',32)),UTC_TIMESTAMP(),UTC_TIMESTAMP(),'attempt-1')", id, event);
    }

    private static int succeed(long id, CountDownLatch ready, CountDownLatch start) throws Exception {
        ready.countDown();
        assertTrue(start.await(10, TimeUnit.SECONDS));
        try {
            return jdbc.update("UPDATE payment SET status=1,version=version+1 WHERE id=? AND status=0 AND version=0", id);
        } catch (org.springframework.dao.DuplicateKeyException expected) {
            return 0;
        }
    }

    private static int changeOrder(int status) {
        return jdbc.update("UPDATE orders SET status=?,version=version+1 WHERE id=1 AND status=0 AND version=0", status);
    }

    private static void snapshot(long id, String operation, Instant expiry) {
        jdbc.update("INSERT INTO api_idempotency VALUES(?,?,'key',?,JSON_OBJECT(),?)",
                id, operation, "a".repeat(64), LocalDateTime.ofInstant(expiry, java.time.ZoneOffset.UTC));
    }

    private static long count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
    }
}
