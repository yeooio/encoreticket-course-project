package io.github.chenforegone.encoreticket.mapper;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import io.github.chenforegone.encoreticket.common.exception.ConflictException;
import io.github.chenforegone.encoreticket.gateway.PaymentChannelEvent;
import io.github.chenforegone.encoreticket.repository.PaymentSettlementRepository;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.mapping.Environment;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 支付结算的真实 MyBatis 映射回归，复用订单测试的显式启用及随机隔离库机制。
 * 只创建专属库并执行生产迁移，不启动应用，不连接或清理业务库，结果库保留供检查。
 *
 * @author c1342
 */
@EnabledIfSystemProperty(named = "payment.settlement.mysql.enabled", matches = "true")
class PaymentSettlementMapperMySqlTest {
    private static final Instant NOW = Instant.parse("2026-10-27T05:00:00Z");
    private static JdbcTemplate jdbc;
    private static PaymentSettlementMapper mapper;
    private static PaymentSettlementRepository repository;
    private static TransactionTemplate transaction;
    private static String unavailable;

    @BeforeAll
    static void setup() throws Exception {
        String password = System.getenv("ORDER_MYSQL_PASSWORD");
        if (password == null || password.isBlank()) {
            unavailable = "未配置 ORDER_MYSQL_PASSWORD，跳过真实 MySQL 映射验收";
            return;
        }
        String server = System.getProperty("order.mysql.server", "jdbc:mysql://127.0.0.1:3308/");
        assertThat(server).matches("jdbc:mysql://127[.]0[.]0[.]1:[0-9]+/");
        String database = "encore_ticket_settlement_test_" + UUID.randomUUID().toString().replace("-", "");
        var admin = new DriverManagerDataSource(server + "?connectionTimeZone=UTC", "root", password);
        try (var connection = admin.getConnection()) {
            assertThat(connection.getMetaData().getDatabaseProductName()).isEqualTo("MySQL");
            try (var statement = connection.createStatement()) {
                statement.execute("CREATE DATABASE " + database);
            }
        } catch (SQLException error) {
            if (error.getSQLState() != null && error.getSQLState().startsWith("08")) {
                unavailable = "隔离 MySQL 不可达，跳过真实映射验收";
                return;
            }
            throw error;
        }
        var dataSource = new DriverManagerDataSource(
                server + database + "?connectionTimeZone=UTC", "root", password);
        jdbc = new JdbcTemplate(dataSource);
        Flyway.configure().dataSource(dataSource).cleanDisabled(true)
                .locations("classpath:db/migration").load().migrate();
        var configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.setEnvironment(new Environment("payment-settlement-regression",
                new SpringManagedTransactionFactory(), dataSource));
        String resource = "mapper/PaymentSettlementMapper.xml";
        try (var stream = Resources.getResourceAsStream(resource)) {
            new XMLMapperBuilder(stream, configuration, resource, configuration.getSqlFragments()).parse();
        }
        if (!configuration.hasMapper(PaymentSettlementMapper.class)) {
            configuration.addMapper(PaymentSettlementMapper.class);
        }
        var session = new SqlSessionTemplate(new MybatisSqlSessionFactoryBuilder().build(configuration));
        mapper = session.getMapper(PaymentSettlementMapper.class);
        repository = new PaymentSettlementRepository(mapper);
        transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    }

    @BeforeEach
    void resetFixture() {
        assumeTrue(unavailable == null, unavailable);
        jdbc.update("DELETE FROM payment_notification");
        jdbc.update("DELETE FROM order_item");
        jdbc.update("DELETE FROM screening_seat");
    }

    @Test
    void ordinaryHashLookupMapsOneBinaryColumnToOneProjection() {
        insertNotification("event", hash());

        var row = mapper.selectNotificationHash("SIM", "event");

        assertThat(row).isNotNull();
        assertThat(row.payloadHash()).hasSize(32).containsExactly(hash());
        assertThat(repository.notificationHash("SIM", "event")).containsExactly(hash());
    }

    @Test
    void lockedHashLookupMapsOneBinaryColumnToOneProjection() {
        insertNotification("event", hash());

        transaction.executeWithoutResult(status -> {
            var row = mapper.selectNotificationHashForUpdate("SIM", "event");
            assertThat(row).isNotNull();
            assertThat(row.payloadHash()).hasSize(32).containsExactly(hash());
        });
    }

    @Test
    void missingHashReturnsNullInBothQueries() {
        assertThat(mapper.selectNotificationHash("SIM", "missing")).isNull();
        assertThat(repository.notificationHash("SIM", "missing")).isNull();
        transaction.executeWithoutResult(status ->
                assertThat(mapper.selectNotificationHashForUpdate("SIM", "missing")).isNull());
    }

    @Test
    void repeatedNotificationReplaysButChangedContentConflictsWithoutOverwritingHash() {
        transaction.executeWithoutResult(status -> repository.notification(event(), hash(), NOW));
        transaction.executeWithoutResult(status -> repository.notification(event(), hash(), NOW));
        byte[] changed = hash();
        changed[31] ^= 1;

        assertThatThrownBy(() -> transaction.executeWithoutResult(
                status -> repository.notification(event(), changed, NOW)))
                .isInstanceOf(ConflictException.class);

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM payment_notification", Long.class)).isEqualTo(1L);
        assertThat(repository.notificationHash("SIM", "event")).containsExactly(hash());
    }

    @Test
    void sellingHeldSeatsRetainsOrderOwnership() {
        insertSeat(101L, 10L, 1);
        insertSeat(102L, 10L, 1);
        insertItem(201L, 101L);
        insertItem(202L, 102L);

        transaction.executeWithoutResult(status -> repository.settleSeats(10L, true, NOW));

        assertSeat(101L, 2, 10L);
        assertSeat(102L, 2, 10L);
        assertThat(mapper.selectSeatIds(10L)).containsExactly(101L, 102L);
    }

    @Test
    void releasingHeldSeatsClearsOwnershipWithoutTouchingAnotherOrdersSeat() {
        insertSeat(101L, 10L, 1);
        insertSeat(102L, 11L, 1);
        insertSeat(103L, 11L, 2);

        transaction.executeWithoutResult(status -> repository.settleSeats(10L, false, NOW));

        assertSeat(101L, 0, null);
        assertSeat(102L, 1, 11L);
        assertSeat(103L, 2, 11L);
        assertThat(mapper.selectSeatIds(10L)).isEmpty();
    }

    @Test
    void conditionalUpdateRejectsAnotherOrderAndAlreadySoldSeats() {
        insertSeat(101L, 11L, 1);
        insertSeat(102L, 10L, 2);

        transaction.executeWithoutResult(status -> {
            assertThat(mapper.updateSeatState(101L, 10L, 0, null, NOW)).isZero();
            assertThat(mapper.updateSeatState(102L, 10L, 0, null, NOW)).isZero();
        });

        assertSeat(101L, 1, 11L);
        assertSeat(102L, 2, 10L);
    }

    @Test
    void laterSeatConflictRollsBackEarlierSeatUpdateInTheCallersTransaction() {
        insertSeat(101L, 10L, 1);
        insertSeat(102L, 10L, 2);
        insertItem(201L, 101L);
        insertItem(202L, 102L);

        assertThatThrownBy(() -> transaction.executeWithoutResult(
                status -> repository.settleSeats(10L, true, NOW)))
                .isInstanceOf(ConflictException.class);

        assertSeat(101L, 1, 10L);
        assertSeat(102L, 2, 10L);
    }

    private void insertNotification(String eventId, byte[] hash) {
        assertThat(mapper.insertNotificationIfAbsent(1L, "SIM", eventId, "P30", "SUCCESS",
                hash, NOW, NOW, "attempt")).isEqualTo(1);
    }

    private void insertSeat(long id, long orderId, int status) {
        var now = LocalDateTime.ofInstant(NOW, ZoneOffset.UTC);
        jdbc.update("INSERT INTO screening_seat(id,seat_id,screening_id,status,order_id,create_time,update_time) VALUES(?,?,40,?,?,?,?)",
                id, id, status, orderId, now, now);
    }

    private void insertItem(long id, long seatId) {
        var now = LocalDateTime.ofInstant(NOW, ZoneOffset.UTC);
        jdbc.update("INSERT INTO order_item(id,order_id,screening_seat_id,unit_price,create_time,update_time) VALUES(?,10,?,88.00,?,?)",
                id, seatId, now, now);
    }

    private void assertSeat(long id, int status, Long orderId) {
        assertThat(jdbc.queryForObject("SELECT status FROM screening_seat WHERE id=?", Integer.class, id))
                .isEqualTo(status);
        assertThat(jdbc.queryForObject("SELECT order_id FROM screening_seat WHERE id=?", Long.class, id))
                .isEqualTo(orderId);
    }

    private static byte[] hash() {
        byte[] hash = new byte[32];
        for (int index = 0; index < hash.length; index++) {
            hash[index] = (byte) (index * 17);
        }
        return hash;
    }

    private PaymentChannelEvent event() {
        return new PaymentChannelEvent("SIM", "event", "P30", "attempt", "SUCCESS",
                new BigDecimal("88.00"), "CNY", true);
    }
}
