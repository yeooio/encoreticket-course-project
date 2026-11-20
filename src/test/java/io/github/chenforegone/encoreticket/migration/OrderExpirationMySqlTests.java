package io.github.chenforegone.encoreticket.migration;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import io.github.chenforegone.encoreticket.mapper.OrderMapper;
import io.github.chenforegone.encoreticket.model.enums.OrderStatus;
import io.github.chenforegone.encoreticket.service.transaction.OrderExpirationProcessor;
import io.github.chenforegone.encoreticket.service.impl.OrderExpirationService;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.mapping.Environment;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

import java.net.URI;
import java.time.Instant;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * 通过真实 MySQL 和 Spring 事务验证订单过期与座位释放的原子性。
 *
 * @author c1342
 */
@EnabledIfSystemProperty(named = "flyway.poc.enabled", matches = "true")
class OrderExpirationMySqlTests {

    private static final Instant NOW = Instant.parse("2026-10-01T11:45:00Z");
    private DriverManagerDataSource control;
    private DriverManagerDataSource dataSource;
    private String schema;
    private boolean schemaCreated;
    private JdbcTemplate jdbc;
    private OrderMapper mapper;
    private OrderExpirationProcessor processor;

    @Test
    void expiresAtTheDeadlineAndReleasesOnlyThatOrdersHeldSeats() {
        insertOrder(1, "2026-10-01 11:45:00", 0);
        insertOrder(2, "2026-10-01 11:46:00", 0);
        insertSeatAndItem(11, 1, 10, 1);
        insertSeatAndItem(22, 2, 10, 1);

        assertTrue(processor.expire(1, NOW));

        assertEquals(OrderStatus.EXPIRED, mapper.selectById(1).getStatus());
        assertEquals(1, mapper.selectById(1).getVersion());
        assertEquals(OrderStatus.WAIT_PAY, mapper.selectById(2).getStatus());
        assertEquals(0, jdbc.queryForObject("SELECT status FROM screening_seat WHERE id=11", Integer.class));
        assertNull(jdbc.queryForObject("SELECT order_id FROM screening_seat WHERE id=11", Long.class));
        assertEquals(1, jdbc.queryForObject("SELECT status FROM screening_seat WHERE id=22", Integer.class));
        assertEquals(2L, jdbc.queryForObject("SELECT order_id FROM screening_seat WHERE id=22", Long.class));
    }

    @ParameterizedTest
    @ValueSource(strings = {"different-screening", "wrong-owner", "sold", "missing-item", "missing-seat"})
    void inconsistentSeatOwnershipRollsBackTheOrderAndEverySeat(String inconsistency) {
        insertOrder(1, "2026-10-01 11:45:00", 0);
        insertSeatAndItem(11, 1, 10, 1);
        insertSeatAndItem(12, 1, 10, 1);
        switch (inconsistency) {
            case "different-screening" -> jdbc.update("UPDATE screening_seat SET screening_id=99 WHERE id=12");
            case "wrong-owner" -> jdbc.update("UPDATE screening_seat SET order_id=99 WHERE id=12");
            case "sold" -> jdbc.update("UPDATE screening_seat SET status=2 WHERE id=12");
            case "missing-item" -> jdbc.update("DELETE FROM order_item WHERE id=12");
            case "missing-seat" -> jdbc.update("DELETE FROM screening_seat WHERE id=12");
            default -> throw new IllegalArgumentException(inconsistency);
        }

        assertThrows(IllegalStateException.class, () -> processor.expire(1, NOW));

        assertEquals(OrderStatus.WAIT_PAY, mapper.selectById(1).getStatus());
        assertEquals(0, mapper.selectById(1).getVersion());
        assertEquals(1, jdbc.queryForObject("SELECT status FROM screening_seat WHERE id=11", Integer.class));
        assertEquals(1L, jdbc.queryForObject("SELECT order_id FROM screening_seat WHERE id=11", Long.class));
    }

    @Test
    void twoExpirationWorkersOnlyReleaseTheOrderOnce() throws Exception {
        insertOrder(1, "2026-10-01 11:45:00", 0);
        insertSeatAndItem(11, 1, 10, 1);
        CountDownLatch start = new CountDownLatch(1);
        try (var workers = Executors.newFixedThreadPool(2)) {
            var first = workers.submit(() -> {
                assertTrue(start.await(10, TimeUnit.SECONDS));
                return processor.expire(1, NOW);
            });
            var second = workers.submit(() -> {
                assertTrue(start.await(10, TimeUnit.SECONDS));
                return processor.expire(1, NOW);
            });
            start.countDown();
            int successfulTransitions = (first.get(10, TimeUnit.SECONDS) ? 1 : 0)
                    + (second.get(10, TimeUnit.SECONDS) ? 1 : 0);
            assertEquals(1, successfulTransitions);
        }
        assertEquals(OrderStatus.EXPIRED, mapper.selectById(1).getStatus());
        assertEquals(1, mapper.selectById(1).getVersion());
        assertEquals(0, jdbc.queryForObject("SELECT status FROM screening_seat WHERE id=11", Integer.class));
        assertNull(jdbc.queryForObject("SELECT order_id FROM screening_seat WHERE id=11", Long.class));
    }

    @Test
    void aSuccessfulConditionalPaymentPreventsExpirationFromReleasingSoldSeats() throws Exception {
        insertOrder(1, "2026-10-01 11:45:00", 0);
        insertSeatAndItem(11, 1, 10, 1);
        // Only models the agreed order-first SQL contention, not a completed payment API.
        try (var workers = Executors.newSingleThreadExecutor(); var connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try (var statement = connection.createStatement()) {
                assertEquals(1, statement.executeUpdate("""
                        UPDATE orders SET status=1 WHERE id=1 AND status=0
                        AND expires_at > '2026-10-01 11:44:59'
                        """));
                assertEquals(1, statement.executeUpdate(
                        "UPDATE screening_seat SET status=2 WHERE id=11 AND order_id=1 AND status=1"));
            }
            var expiration = workers.submit(() -> processor.expire(1, NOW));
            connection.commit();
            assertFalse(expiration.get(10, TimeUnit.SECONDS));
        }
        assertEquals(OrderStatus.PAID, mapper.selectById(1).getStatus());
        assertEquals(2, jdbc.queryForObject("SELECT status FROM screening_seat WHERE id=11", Integer.class));
        assertEquals(1L, jdbc.queryForObject("SELECT order_id FROM screening_seat WHERE id=11", Long.class));
    }

    @Test
    void expirationWinningTheOrderConditionPreventsALatePaymentStateChange() {
        insertOrder(1, "2026-10-01 11:45:00", 0);
        insertSeatAndItem(11, 1, 10, 1);

        assertTrue(processor.expire(1, NOW));
        assertEquals(0, jdbc.update("""
                UPDATE orders SET status=1 WHERE id=1 AND status=0
                AND expires_at > '2026-10-01 11:45:00'
                """));

        assertEquals(OrderStatus.EXPIRED, mapper.selectById(1).getStatus());
        assertEquals(1, mapper.selectById(1).getVersion());
        assertNull(jdbc.queryForObject("SELECT order_id FROM screening_seat WHERE id=11", Long.class));
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 3, 4, 5})
    void nonPendingOrdersRemainUnchanged(int status) {
        insertOrder(1, "2026-10-01 11:45:00", status);
        insertSeatAndItem(11, 1, 10, 2);

        assertFalse(processor.expire(1, NOW));

        assertEquals(java.util.Arrays.stream(OrderStatus.values()).filter(value -> value.getCode() == status).findFirst().orElseThrow(), mapper.selectById(1).getStatus());
        assertEquals(2, jdbc.queryForObject("SELECT status FROM screening_seat WHERE id=11", Integer.class));
    }

    @Test
    void queryCleanupIsBoundedAndDoesNotExpireStillPayableOrders() {
        insertOrder(1, "2026-10-01 11:45:00", 0);
        insertOrder(2, "2026-10-01 11:45:00", 0);
        insertOrder(3, "2026-10-01 11:46:00", 0);
        insertSeatAndItem(11, 1, 10, 1);
        insertSeatAndItem(22, 2, 10, 1);
        insertSeatAndItem(33, 3, 10, 1);
        OrderExpirationService service = new OrderExpirationService(mapper, processor,
                Clock.fixed(NOW, ZoneOffset.UTC), 1);

        service.expireForScreening(10);

        assertEquals(OrderStatus.EXPIRED, mapper.selectById(1).getStatus());
        assertEquals(1, mapper.selectById(1).getVersion());
        assertEquals(OrderStatus.WAIT_PAY, mapper.selectById(2).getStatus());
        assertEquals(1, jdbc.queryForObject("SELECT status FROM screening_seat WHERE id=22", Integer.class));
        service.expireForScreening(10);
        assertEquals(OrderStatus.EXPIRED, mapper.selectById(2).getStatus());
        assertEquals(OrderStatus.WAIT_PAY, mapper.selectById(3).getStatus());
        assertFalse(processor.expire(3, NOW));
        assertEquals(OrderStatus.WAIT_PAY, mapper.selectById(3).getStatus());
        assertEquals(0, mapper.selectById(3).getVersion());
        assertEquals(1, jdbc.queryForObject("SELECT status FROM screening_seat WHERE id=33", Integer.class));
        assertEquals(3L, jdbc.queryForObject("SELECT order_id FROM screening_seat WHERE id=33", Long.class));
    }

    @ParameterizedTest
    @ValueSource(strings = {"expires_at", "create_time", "sale_end_time"})
    void earliestPaymentDeadlineControlsSelectionAndExpiration(String deadlineSource) {
        insertOrder(1, "2026-10-01 11:46:00", 0);
        insertSeatAndItem(11, 1, 10, 1);
        switch (deadlineSource) {
            case "expires_at" -> jdbc.update("UPDATE orders SET expires_at='2026-10-01 11:45:00' WHERE id=1");
            case "create_time" -> jdbc.update("UPDATE orders SET create_time='2026-10-01 11:30:00' WHERE id=1");
            case "sale_end_time" -> jdbc.update("""
                    UPDATE screening SET start_time='2026-10-01 20:00:00', end_time='2026-10-01 22:00:00',
                        sale_end_time='2026-10-01 19:45:00', admission_open_time='2026-10-01 19:45:00'
                    WHERE id=10
                    """);
            default -> throw new IllegalArgumentException(deadlineSource);
        }
        OrderExpirationService service = new OrderExpirationService(mapper, processor,
                Clock.fixed(NOW.minusSeconds(1), ZoneOffset.UTC), 1);
        service.expireForScreening(10);
        assertFalse(processor.expire(1, NOW.minusSeconds(1)));
        assertEquals(OrderStatus.WAIT_PAY, mapper.selectById(1).getStatus());
        assertEquals(0, mapper.selectById(1).getVersion());
        assertEquals(1, jdbc.queryForObject("SELECT status FROM screening_seat WHERE id=11", Integer.class));
        assertEquals(1L, jdbc.queryForObject("SELECT order_id FROM screening_seat WHERE id=11", Long.class));

        new OrderExpirationService(mapper, processor, Clock.fixed(NOW, ZoneOffset.UTC), 1)
                .expireForScreening(10);
        assertEquals(OrderStatus.EXPIRED, mapper.selectById(1).getStatus());
        assertEquals(1, mapper.selectById(1).getVersion());
        assertFalse(processor.expire(1, NOW));
        assertEquals(0, jdbc.queryForObject("SELECT status FROM screening_seat WHERE id=11", Integer.class));
        assertNull(jdbc.queryForObject("SELECT order_id FROM screening_seat WHERE id=11", Long.class));
    }

    @Test
    void scheduledCursorPassesAnInvalidOrderAndRetriesItOnTheNextPass() {
        insertOrder(1, "2026-10-01 11:45:00", 0);
        insertOrder(2, "2026-10-01 11:45:00", 0);
        insertSeatAndItem(11, 1, 99, 1);
        insertSeatAndItem(22, 2, 10, 1);
        OrderExpirationService service = new OrderExpirationService(mapper, processor,
                Clock.fixed(NOW, ZoneOffset.UTC), 1);

        service.expireDueOrders();
        assertEquals(OrderStatus.WAIT_PAY, mapper.selectById(1).getStatus());
        assertEquals(0, mapper.selectById(1).getVersion());
        assertEquals(OrderStatus.WAIT_PAY, mapper.selectById(2).getStatus());
        service.expireDueOrders();
        assertEquals(OrderStatus.EXPIRED, mapper.selectById(2).getStatus());
        jdbc.update("UPDATE screening_seat SET screening_id=10 WHERE id=11");
        service.expireDueOrders();
        assertEquals(OrderStatus.EXPIRED, mapper.selectById(1).getStatus());
        assertEquals(1, mapper.selectById(1).getVersion());
    }

    @BeforeEach
    void createIsolatedSchema() throws Exception {
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
        control = new DriverManagerDataSource(url, username, password);
        schema = "encore_ticket_flyway_poc_expiry_" + UUID.randomUUID().toString().replace("-", "");
        try (var connection = control.getConnection(); var statement = connection.createStatement()) {
            assertEquals("MySQL", connection.getMetaData().getDatabaseProductName());
            assertEquals(8, connection.getMetaData().getDatabaseMajorVersion());
            assertEquals(4, connection.getMetaData().getDatabaseMinorVersion());
            statement.executeUpdate("CREATE DATABASE `" + schema + "` CHARACTER SET utf8mb4");
            schemaCreated = true;
        }
        dataSource = new DriverManagerDataSource("jdbc:mysql://" + uri.getRawAuthority() + "/" + schema
                + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery()), username, password);
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").target("26")
                .createSchemas(false).cleanDisabled(true).load().migrate();
        jdbc = new JdbcTemplate(dataSource);
        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.setEnvironment(new Environment("order-expiration-test",
                new SpringManagedTransactionFactory(), dataSource));
        String resource = "mapper/OrderMapper.xml";
        try (var stream = Resources.getResourceAsStream(resource)) {
            new XMLMapperBuilder(stream, configuration, resource, configuration.getSqlFragments()).parse();
        }
        mapper = new SqlSessionTemplate(new MybatisSqlSessionFactoryBuilder().build(configuration))
                .getMapper(OrderMapper.class);
        ProxyFactory proxy = new ProxyFactory(new OrderExpirationProcessor(mapper));
        proxy.setProxyTargetClass(true);
        proxy.addAdvice(new TransactionInterceptor(new DataSourceTransactionManager(dataSource),
                new AnnotationTransactionAttributeSource()));
        processor = (OrderExpirationProcessor) proxy.getProxy();
    }

    @AfterEach
    void dropOnlyThisTestsSchema() {
        if (schemaCreated && schema.matches("encore_ticket_flyway_poc_expiry_[a-f0-9]{32}")) {
            new JdbcTemplate(control).execute("DROP DATABASE `" + schema + "`");
        }
    }

    private void insertOrder(long id, String expiresAt, int status) {
        if (jdbc.queryForObject("SELECT COUNT(*) FROM screening WHERE id=10", Long.class) == 0) {
            jdbc.update("""
                    INSERT INTO screening (id, movie_id, hall_id, price, start_time, end_time,
                        sale_start_time, sale_end_time, admission_open_time, duration_minutes_snapshot,
                        turnaround_minutes_snapshot, status, version, create_time, update_time)
                    VALUES (10, 1, 1, 30.00, '2026-10-01 21:00:00', '2026-10-01 23:00:00',
                        '2026-10-01 08:00:00', '2026-10-01 20:45:00', '2026-10-01 20:45:00',
                        120, 15, 1, 0, '2026-10-01 00:00:00', '2026-10-01 00:00:00')
                    """);
        }
        jdbc.update("""
                INSERT INTO orders (id, order_no, user_id, screening_id, total_amount, expires_at, status,
                    create_time, update_time)
                VALUES (?, ?, 100, 10, 30.00, ?, ?, DATE_SUB(?, INTERVAL 15 MINUTE), '2026-10-01 11:30:00')
                """, id, "expiry-test-" + id, expiresAt, status, expiresAt);
    }

    private void insertSeatAndItem(long seatId, long orderId, long screeningId, int status) {
        jdbc.update("""
                INSERT INTO screening_seat (id, seat_id, screening_id, status, order_id, create_time, update_time)
                VALUES (?, ?, ?, ?, ?, '2026-10-01 11:30:00', '2026-10-01 11:30:00')
                """, seatId, seatId, screeningId, status, orderId);
        jdbc.update("""
                INSERT INTO order_item (id, order_id, screening_seat_id, unit_price, create_time, update_time)
                VALUES (?, ?, ?, 30.00, '2026-10-01 11:30:00', '2026-10-01 11:30:00')
                """, seatId, orderId, seatId);
    }
}
