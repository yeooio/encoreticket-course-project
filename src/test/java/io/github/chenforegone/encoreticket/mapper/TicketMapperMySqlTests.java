package io.github.chenforegone.encoreticket.mapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.chenforegone.encoreticket.model.enums.TicketDisplayStatus;
import io.github.chenforegone.encoreticket.model.query.TicketProjection;
import io.github.chenforegone.encoreticket.model.query.TicketQueryFilter;
import java.net.URI;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * TicketMapper 的隔离 MySQL 集成测试。
 *
 * @author c1342
 */
@SpringBootTest
@EnabledIfSystemProperty(named = "ticket.mapper.mysql.enabled", matches = "true")
class TicketMapperMySqlTests {
    private static final long USER_ID = 101L;
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 19, 12, 0);

    @Autowired
    private DataSource dataSource;

    @Autowired
    private TicketMapper ticketMapper;

    @DynamicPropertySource
    static void mysqlProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", TicketMapperMySqlTests::safePocUrl);
        registry.add("spring.datasource.username", () -> System.getProperty("ticket.mapper.mysql.username", "encore_ticket"));
        registry.add("spring.datasource.password", TicketMapperMySqlTests::password);
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.flyway.locations", () -> "classpath:db/migration");
        registry.add("spring.flyway.baseline-on-migrate", () -> "false");
    }

    @BeforeEach
    void resetFixture() throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.executeUpdate("DELETE FROM ticket_verification");
            statement.executeUpdate("DELETE FROM ticket");
            statement.executeUpdate("DELETE FROM order_item");
            statement.executeUpdate("DELETE FROM screening_seat");
            statement.executeUpdate("DELETE FROM orders");
            statement.executeUpdate("DELETE FROM screening");
            statement.executeUpdate("DELETE FROM seat");
            statement.executeUpdate("DELETE FROM movie");
            statement.executeUpdate("DELETE FROM hall");
            statement.executeUpdate("DELETE FROM sys_user");
            statement.executeUpdate("INSERT INTO sys_user (id, username, password, status, create_time, update_time) VALUES (101, 'mapper-user', 'x', 0, '2026-01-01 00:00:00', '2026-01-01 00:00:00'), (202, 'other-user', 'x', 0, '2026-01-01 00:00:00', '2026-01-01 00:00:00')");
            statement.executeUpdate("INSERT INTO hall (id, hall_name, hall_no, status, create_time, update_time) VALUES (1, '一号厅', 'H1', 0, '2026-01-01 00:00:00', '2026-01-01 00:00:00')");
            statement.executeUpdate("INSERT INTO movie (id, title, status, release_date, duration_minutes, create_time, update_time) VALUES (1, '特殊%_!\\\\片', 0, '2026-01-01', 120, '2026-01-01 00:00:00', '2026-01-01 00:00:00'), (2, '普通片', 0, '2026-01-01', 120, '2026-01-01 00:00:00', '2026-01-01 00:00:00')");
            statement.executeUpdate("INSERT INTO screening (id, price, movie_id, hall_id, start_time, end_time, admission_open_time, duration_minutes_snapshot, sale_start_time, sale_end_time, status, create_time, update_time) VALUES (1, 50, 1, 1, '2026-10-20 10:00:00', '2026-10-20 12:00:00', '2026-10-20 09:45:00', 120, '2026-10-19 00:00:00', '2026-10-20 09:45:00', 1, '2026-01-01 00:00:00', '2026-01-01 00:00:00'), (2, 50, 2, 1, '2026-10-18 10:00:00', '2026-10-18 12:00:00', '2026-10-18 09:45:00', 120, '2026-10-17 00:00:00', '2026-10-18 09:45:00', 4, '2026-01-01 00:00:00', '2026-01-01 00:00:00')");
            statement.executeUpdate("INSERT INTO seat (id, hall_id, `row_number`, seat_number, status, create_time, update_time) VALUES (1, 1, 1, 1, 0, '2026-01-01 00:00:00', '2026-01-01 00:00:00'), (2, 1, 1, 2, 0, '2026-01-01 00:00:00', '2026-01-01 00:00:00')");
            statement.executeUpdate("INSERT INTO screening_seat (id, seat_id, screening_id, status, order_id, create_time, update_time) VALUES (1, 1, 1, 2, 1, '2026-01-01 00:00:00', '2026-01-01 00:00:00'), (2, 2, 2, 2, 2, '2026-01-01 00:00:00', '2026-01-01 00:00:00')");
            statement.executeUpdate("INSERT INTO orders (id, order_no, user_id, screening_id, total_amount, expires_at, status, version, create_time, update_time) VALUES (1, 'ORD-1', 101, 1, 50, '2026-10-21 00:00:00', 1, 0, '2026-01-01 00:00:00', '2026-01-01 00:00:00'), (2, 'ORD-2', 101, 2, 50, '2026-10-19 00:00:00', 4, 0, '2026-01-01 00:00:00', '2026-01-01 00:00:00'), (3, 'ORD-3', 202, 1, 50, '2026-10-21 00:00:00', 1, 0, '2026-01-01 00:00:00', '2026-01-01 00:00:00'), (4, 'ORD-4', 101, 1, 50, '2026-10-21 00:00:00', 0, 0, '2026-01-01 00:00:00', '2026-01-01 00:00:00')");
            statement.executeUpdate("INSERT INTO order_item (id, order_id, screening_seat_id, unit_price, create_time, update_time) VALUES (1, 1, 1, 50, '2026-01-01 00:00:00', '2026-01-01 00:00:00'), (2, 2, 2, 50, '2026-01-01 00:00:00', '2026-01-01 00:00:00')");
            statement.executeUpdate("INSERT INTO ticket (id, status, order_item_id, ticket_code, create_time, update_time) VALUES (1, 0, 1, 'CODE-1', '2026-01-01 00:00:00', '2026-01-01 00:00:00'), (2, 0, 2, 'CODE-2', '2026-01-01 00:00:00', '2026-01-01 00:00:00')");
        }
    }

    @Test
    void countAndSelectShareFiltersAndPreserveProjection() {
        TicketQueryFilter filter = new TicketQueryFilter(USER_ID, null, "特殊%_!\\", null, NOW, 0, 10);

        assertEquals(1, ticketMapper.count(filter));
        List<TicketProjection> rows = ticketMapper.selectPage(filter);
        assertEquals(1, rows.size());
        assertEquals(1L, rows.get(0).id());
        assertEquals("CODE-1", rows.get(0).ticketCode());
        assertEquals(1, ticketMapper.count(new TicketQueryFilter(USER_ID, TicketDisplayStatus.VALID, null, null, NOW, 0, 10)));
    }

    @Test
    void ownedQueriesHideOtherUsersAndAcceptValidData() {
        assertNotNull(ticketMapper.selectOwned(1, USER_ID));
        assertNull(ticketMapper.selectOwned(1, 202));
        assertFalse(ticketMapper.hasInvalidOwned(new TicketQueryFilter(USER_ID, TicketDisplayStatus.VALID, null, null, NOW, 0, 10)));
        assertFalse(ticketMapper.hasInvalidOwned(new TicketQueryFilter(202, null, null, null, NOW, 0, 10)));
    }

    @Test
    void detectsValidTicketOnRefundedOrderRegardlessOfFilters() throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.executeUpdate("UPDATE orders SET status = 5 WHERE id = 2");
        }

        TicketQueryFilter filter = new TicketQueryFilter(USER_ID, TicketDisplayStatus.VALID, null, "ORD-1", NOW, 0, 10);
        assertTrue(ticketMapper.hasInvalidOwned(filter));
        assertFalse(ticketMapper.hasInvalidOwned(new TicketQueryFilter(202, null, null, null, NOW, 0, 10)));
    }

    @Test
    void voidTicketRemainsQueryableAfterSeatReleaseOrReassignment() throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.executeUpdate("UPDATE orders SET status = 5 WHERE id = 1");
            statement.executeUpdate("UPDATE ticket SET status = 2 WHERE id = 1");
            statement.executeUpdate("UPDATE screening_seat SET status = 0, order_id = NULL WHERE id = 1");
        }

        TicketQueryFilter filter = new TicketQueryFilter(USER_ID, TicketDisplayStatus.VOID, null, null, NOW, 0, 10);
        assertEquals(1, ticketMapper.count(filter));
        assertEquals(List.of(1L), ticketMapper.selectPage(filter).stream().map(TicketProjection::id).toList());
        TicketProjection released = ticketMapper.selectOwned(1, USER_ID);
        assertNotNull(released);
        assertEquals(1L, released.orderId());
        assertEquals(1L, released.screeningSeatId());
        assertEquals(1L, released.seatId());
        assertFalse(ticketMapper.hasInvalidOwned(filter));

        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.executeUpdate("UPDATE screening_seat SET status = 2, order_id = 3 WHERE id = 1");
        }

        assertEquals(1, ticketMapper.count(filter));
        assertEquals(List.of(1L), ticketMapper.selectPage(filter).stream().map(TicketProjection::id).toList());
        assertEquals(1L, ticketMapper.selectOwned(1, USER_ID).orderId());
        assertNull(ticketMapper.selectOwned(1, 202));
        assertFalse(ticketMapper.hasInvalidOwned(filter));
    }

    @Test
    void mismatchedScreeningSeatPreservesOwnershipAndExposesMissingAssociation() throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.executeUpdate("UPDATE screening_seat SET screening_id = 2 WHERE id = 1");
        }

        TicketProjection projection = ticketMapper.selectOwned(1, USER_ID);
        assertNotNull(projection);
        assertEquals(USER_ID, projection.userId());
        assertEquals(1L, projection.screeningId());
        assertNull(projection.screeningSeatId());
        assertNull(projection.seatId());
        TicketQueryFilter filter = new TicketQueryFilter(USER_ID, TicketDisplayStatus.VALID, null, null, NOW, 0, 10);
        assertTrue(ticketMapper.hasInvalidOwned(filter));
        assertEquals(0, ticketMapper.count(filter));
        assertTrue(ticketMapper.selectPage(filter).isEmpty());
        assertNull(ticketMapper.selectOwned(1, 202));
    }

    @Test
    void mismatchedSeatHallPreservesOwnershipAndExposesMissingAssociation() throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.executeUpdate("INSERT INTO hall (id, hall_name, hall_no, status, create_time, update_time) VALUES (2, '二号厅', 'H2', 0, '2026-01-01 00:00:00', '2026-01-01 00:00:00')");
            statement.executeUpdate("UPDATE seat SET hall_id = 2 WHERE id = 1");
        }

        TicketProjection projection = ticketMapper.selectOwned(1, USER_ID);
        assertNotNull(projection);
        assertEquals(USER_ID, projection.userId());
        assertEquals(1L, projection.screeningSeatId());
        assertNull(projection.seatId());
        assertNull(projection.rowNumber());
        assertNull(projection.seatNumber());
        TicketQueryFilter filter = new TicketQueryFilter(USER_ID, TicketDisplayStatus.VALID, null, null, NOW, 0, 10);
        assertTrue(ticketMapper.hasInvalidOwned(filter));
        assertEquals(0, ticketMapper.count(filter));
        assertTrue(ticketMapper.selectPage(filter).isEmpty());
        assertNull(ticketMapper.selectOwned(1, 202));
    }

    @Test
    void explainPageQuery() throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement(); ResultSet resultSet = statement.executeQuery(
                "EXPLAIN SELECT t.id FROM ticket t LEFT JOIN order_item oi ON oi.id=t.order_item_id LEFT JOIN orders o ON o.id=oi.order_id LEFT JOIN screening s ON s.id=o.screening_id LEFT JOIN movie m ON m.id=s.movie_id LEFT JOIN hall h ON h.id=s.hall_id WHERE o.user_id=101 AND t.status IN (0,1,2) ORDER BY s.start_time DESC,t.id DESC LIMIT 10")) {
            assertTrue(resultSet.next());
        }
    }

    private static String password() {
        String value = System.getenv("TICKET_MAPPER_MYSQL_PASSWORD");
        if (value == null || value.isBlank()) {
            value = System.getenv("MYSQL_PASSWORD");
        }
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Missing TICKET_MAPPER_MYSQL_PASSWORD or MYSQL_PASSWORD");
        }
        return value;
    }

    private static String safePocUrl() {
        String url = System.getProperty("ticket.mapper.mysql.url");
        if (url == null || !url.startsWith("jdbc:mysql://")) {
            throw new IllegalArgumentException("-Dticket.mapper.mysql.url must be a MySQL JDBC URL");
        }
        String database = URI.create(url.substring("jdbc:".length())).getPath();
        if (database == null || !database.startsWith("/encore_ticket_flyway_poc_")) {
            throw new IllegalArgumentException("TicketMapperMySqlTests only accepts encore_ticket_flyway_poc_* schemas");
        }
        return url;
    }
}
