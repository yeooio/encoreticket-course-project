package io.github.chenforegone.encoreticket.service;

import io.github.chenforegone.encoreticket.service.transaction.TicketVerificationTransaction;
import io.github.chenforegone.encoreticket.config.ScreeningHistoricalPrecheckCallback;

import io.github.chenforegone.encoreticket.common.exception.TicketVerificationException;

import io.github.chenforegone.encoreticket.converter.TicketVerificationConverter;
import io.github.chenforegone.encoreticket.mapper.TicketVerificationMapper;
import io.github.chenforegone.encoreticket.model.dto.TicketVerificationRequest;
import io.github.chenforegone.encoreticket.model.dto.TicketVerificationQueryRequest;
import io.github.chenforegone.encoreticket.service.impl.TicketVerificationServiceImpl;
import io.github.chenforegone.encoreticket.common.error.ErrorCode;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * 显式启用的真实 MySQL 测试，只接受空的 encore_ticket_verification_test_* 隔离库。
 *
 * @author c1342
 */
@EnabledIfSystemProperty(named="ticket.verification.mysql.enabled",matches="true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TicketVerificationMySqlTest {
    @TempDir static Path migrationDirectory;
    private DriverManagerDataSource dataSource;
    private JdbcTemplate jdbc;
    private TicketVerificationServiceImpl service;
    private TicketVerificationTransaction transaction;
    private TransactionTemplate other;
    private final JsonMapper json=JsonMapper.builder().build();
    private final Instant now=Instant.parse("2026-10-19T12:00:00Z");

    @BeforeAll
    void prepare() throws Exception {
        String url=System.getProperty("ticket.verification.mysql.url","");
        if (!url.matches("jdbc:mysql://[^/]+/encore_ticket_verification_test_[A-Za-z0-9_]+(\\?.*)?")) {
            throw new IllegalArgumentException("仅允许显式指定隔离测试库 encore_ticket_verification_test_*");
        }
        var ds=new DriverManagerDataSource(url,System.getProperty("ticket.verification.mysql.user","encore_ticket"),
                System.getenv("TICKET_VERIFICATION_MYSQL_PASSWORD"));
        dataSource=ds;
        jdbc=new JdbcTemplate(ds);
        try (var connection=ds.getConnection()) {
            assertEquals("MySQL",connection.getMetaData().getDatabaseProductName());
        } catch (SQLException unavailable) {
            Assumptions.abort("隔离 MySQL 不可连接，跳过真实数据库测试");
        }
        assertEquals(0L,jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE()",Long.class),"必须使用空的隔离测试库");
        // 复制生产迁移原文至独立位置，不加载 V3 等其他业务迁移。
        for (String script:List.of("V1__baseline_schema.sql","V2__seed_roles.sql",
                "V20__screening_admission_support.sql","V21__ticket_verification_idempotency.sql")) {
            try (var source=new ClassPathResource("db/migration/"+script).getInputStream()) {
                Files.copy(source,migrationDirectory.resolve(script));
            }
        }
        var manager=new DataSourceTransactionManager(ds);
        other=new TransactionTemplate(manager);
        var mapper=new TicketVerificationMapper(jdbc);
        var converter=new TicketVerificationConverter();
        transaction=new TicketVerificationTransaction(mapper,converter,()->now,json,manager);
        service=new TicketVerificationServiceImpl(transaction,mapper,converter);
    }

    private Flyway migrations(String target) {
        return Flyway.configure().dataSource(dataSource)
                .locations("filesystem:"+migrationDirectory.toAbsolutePath().toString().replace('\\','/'))
                .callbacks(new ScreeningHistoricalPrecheckCallback())
                .target(target).cleanDisabled(false).load();
    }

    @BeforeEach
    void fixture(TestInfo test) {
        // 仅在本类已确认初始为空的专属隔离库内重置，失败的 DDL 也不会污染下一项。
        migrations("21").clean();
        boolean historical=test.getTestMethod().orElseThrow().getName().startsWith("migrationHistorical");
        migrations(historical?"2":"21").migrate();
        if (historical || test.getTestMethod().orElseThrow().getName().equals("migrationEmptyDatabase")) { return; }
        jdbc.update("INSERT INTO sys_user(id,username,password,status,create_time,update_time) VALUES(7,'staff','unused',0,UTC_TIMESTAMP(),UTC_TIMESTAMP())");
        jdbc.update("INSERT INTO movie(id,title,status,create_time,update_time) VALUES(10,'电影',0,UTC_TIMESTAMP(),UTC_TIMESTAMP())");
        jdbc.update("INSERT INTO hall(id,hall_name,hall_no,status,create_time,update_time) VALUES(8,'厅','H',0,UTC_TIMESTAMP(),UTC_TIMESTAMP())");
        jdbc.update("INSERT INTO screening(id,price,movie_id,hall_id,start_time,end_time,sale_start_time,sale_end_time,status,admission_open_time,create_time,update_time) VALUES(3,50,10,8,'2026-10-19 20:15:00','2026-10-19 22:00:00','2026-10-01 12:00:00','2026-10-19 20:00:00',2,'2026-10-19 20:00:00',UTC_TIMESTAMP(),UTC_TIMESTAMP())");
        jdbc.update("INSERT INTO seat(id,hall_id,`row_number`,seat_number,create_time,update_time) VALUES(6,8,1,2,UTC_TIMESTAMP(),UTC_TIMESTAMP())");
        jdbc.update("INSERT INTO orders(id,order_no,user_id,screening_id,total_amount,expires_at,status,create_time,update_time) VALUES(2,'ORDER',7,3,50,UTC_TIMESTAMP(),1,UTC_TIMESTAMP(),UTC_TIMESTAMP())");
        jdbc.update("INSERT INTO screening_seat(id,seat_id,screening_id,status,order_id,create_time,update_time) VALUES(5,6,3,2,2,UTC_TIMESTAMP(),UTC_TIMESTAMP())");
        jdbc.update("INSERT INTO order_item(id,order_id,screening_seat_id,unit_price,create_time,update_time) VALUES(4,2,5,50,UTC_TIMESTAMP(),UTC_TIMESTAMP())");
        jdbc.update("INSERT INTO ticket(id,status,order_item_id,ticket_code,create_time,update_time) VALUES(1,0,4,'Code',UTC_TIMESTAMP(),UTC_TIMESTAMP())");
    }

    @Test
    void migrationEmptyDatabase() {
        assertEquals(List.of("1","2","20","21"),jdbc.queryForList(
                "SELECT version FROM flyway_schema_history WHERE success=1 ORDER BY installed_rank",String.class));
        assertEquals(3L,jdbc.queryForObject("SELECT COUNT(*) FROM role",Long.class));
        assertEquals("NO",jdbc.queryForObject("SELECT is_nullable FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='screening' AND column_name='admission_open_time'",String.class));
        assertEquals("ascii_bin",jdbc.queryForObject("SELECT collation_name FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='api_idempotency' AND column_name='idempotency_key'",String.class));
        assertEquals(0,migrations("21").migrate().migrationsExecuted);
    }

    @Test
    void migrationHistoricalBackfillsAdmissionAndVersionAndEnforcesConstraint() {
        historicalScreening(1,"2026-10-19 20:00:00","2026-10-19 22:00:00");
        assertEquals(2,migrations("21").migrate().migrationsExecuted);
        assertEquals(LocalDateTime.of(2026,10,19,19,45),jdbc.queryForObject(
                "SELECT admission_open_time FROM screening WHERE id=1",LocalDateTime.class));
        assertEquals(0,jdbc.queryForObject("SELECT version FROM screening WHERE id=1",Integer.class));
        var failure=assertThrows(org.springframework.dao.DataAccessException.class,()->jdbc.update(
                "UPDATE screening SET admission_open_time='2026-10-19 20:00:01' WHERE id=1"));
        var sqlFailure=assertInstanceOf(SQLException.class,failure.getMostSpecificCause());
        assertEquals(3819,sqlFailure.getErrorCode());
        assertEquals(LocalDateTime.of(2026,10,19,20,0),jdbc.queryForObject(
                "SELECT start_time FROM screening WHERE id=1",LocalDateTime.class));
    }

    @Test
    void migrationHistoricalPrecheckRejectsInsufficientTurnaroundBeforeAddingColumns() {
        historicalScreening(1,"2026-10-19 20:00:00","2026-10-19 22:00:00");
        historicalScreening(2,"2026-10-19 22:14:00","2026-10-20 00:00:00");
        FlywayException failure=assertThrows(FlywayException.class,()->migrations("21").migrate());
        assertTrue(failure.getMessage().contains("V20__screening_admission_support.sql"));
        assertTrue(failure.getMessage().contains("screening historical schedule precheck failed"),
                "必须因历史排片预检失败，而非无关 SQL 或连接错误");
        assertEquals(0L,jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='screening' AND column_name IN ('admission_open_time','version')",Long.class));
        assertEquals(0L,jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='api_idempotency'",Long.class));
        assertEquals(LocalDateTime.of(2026,10,19,22,14),jdbc.queryForObject("SELECT start_time FROM screening WHERE id=2",LocalDateTime.class));
        assertEquals(0L,jdbc.queryForObject("SELECT COUNT(*) FROM flyway_schema_history WHERE version IN ('20','21') AND success=1",Long.class));
    }

    @Test
    void migrationHistoricalExactTurnaroundRemainsValidAndAppliedV20IsNotRechecked() {
        historicalScreening(1,"2026-10-19 20:00:00","2026-10-19 22:00:00");
        historicalScreening(2,"2026-10-19 22:15:00","2026-10-20 00:00:00");
        assertEquals(2,migrations("21").migrate().migrationsExecuted);
        Integer checksum=jdbc.queryForObject("SELECT checksum FROM flyway_schema_history WHERE version='20' AND success=1",Integer.class);
        // 已应用 V20 的实例不应在重启时重新执行历史预检。
        jdbc.update("UPDATE screening SET start_time='2026-10-19 22:14:00' WHERE id=2");
        assertDoesNotThrow(()->migrations("21").validate());
        assertEquals(0,migrations("21").migrate().migrationsExecuted);
        assertEquals(checksum,jdbc.queryForObject("SELECT checksum FROM flyway_schema_history WHERE version='20' AND success=1",Integer.class));
    }

    private void historicalScreening(long id,String start,String end) {
        jdbc.update("INSERT INTO screening(id,price,movie_id,hall_id,start_time,end_time,sale_start_time,sale_end_time,status,create_time,update_time) VALUES(?,50,10,8,?,?,'2026-10-01 12:00:00','2026-10-19 19:00:00',0,UTC_TIMESTAMP(),UTC_TIMESTAMP())",id,start,end);
    }

    @Test
    void sameKeyConcurrentReturnsSameCommittedResultAndAdminCanQuery() throws Exception {
        var results=concurrent("Key","Key");
        assertEquals(results.get(0),results.get(1));
        assertEquals(1L,count("ticket_verification")); assertEquals(1L,count("api_idempotency"));
        var page=service.list(new TicketVerificationQueryRequest());
        assertEquals(1,page.total()); assertEquals("ORDER",page.items().getFirst().orderNo());
        jdbc.update("UPDATE screening SET status=4");
        assertEquals(results.getFirst(),service.verify(7,"Key",request("Code")));
    }

    @Test
    void differentKeysOnlyOneSucceeds() throws Exception {
        var results=concurrent("Key","key");
        assertEquals(1,results.stream().filter(v->v instanceof io.github.chenforegone.encoreticket.model.vo.TicketVerificationVO).count());
        assertTrue(results.contains(ErrorCode.TICKET_ALREADY_USED));
        assertEquals(1L,count("ticket_verification")); assertEquals(1L,count("api_idempotency"));
    }

    @Test
    void codesAreCaseSensitiveAndRequestConflictDoesNotLeakAudit() throws Exception {
        assertEquals(ErrorCode.RESOURCE_NOT_FOUND,assertThrows(TicketVerificationException.class,
                ()->service.verify(7,"key",request("code"))).getErrorCode());
        service.verify(7,"key",request("Code"));
        assertEquals(ErrorCode.IDEMPOTENCY_CONFLICT,assertThrows(TicketVerificationException.class,
                ()->service.verify(7,"key",request("CODE"))).getErrorCode());
        var failure=assertThrows(TicketVerificationException.class,()->service.verify(7,"other",request("Code")));
        assertEquals("票券已核销",failure.getMessage());
    }

    @Test
    void auditStorageFailureRollsBackTicketAndReservation() throws Exception {
        jdbc.execute("CREATE TRIGGER verification_fail BEFORE INSERT ON ticket_verification FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='injected'");
        try { assertThrows(RuntimeException.class,()->service.verify(7,"key",request("Code"))); }
        finally { jdbc.execute("DROP TRIGGER verification_fail"); }
        assertEquals(0,jdbc.queryForObject("SELECT status FROM ticket WHERE id=1",Integer.class));
        assertEquals(0L,count("api_idempotency")); assertEquals(0L,count("ticket_verification"));
        assertNotNull(service.verify(7,"key",request("Code")));
    }

    @Test
    void responseStorageFailureAlsoRollsBackAudit() throws Exception {
        jdbc.execute("CREATE TRIGGER snapshot_fail BEFORE UPDATE ON api_idempotency FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='injected'");
        try { assertThrows(RuntimeException.class,()->service.verify(7,"key",request("Code"))); }
        finally { jdbc.execute("DROP TRIGGER snapshot_fail"); }
        assertEquals(0,jdbc.queryForObject("SELECT status FROM ticket WHERE id=1",Integer.class));
        assertEquals(0L,count("api_idempotency")); assertEquals(0L,count("ticket_verification"));
    }

    @Test
    void expiryAndCleanupPreserveAuditAndOtherOperations() throws Exception {
        service.verify(7,"Key",request("Code"));
        jdbc.update("UPDATE api_idempotency SET expires_at=?",LocalDateTime.of(2026,10,19,12,0));
        jdbc.update("INSERT INTO api_idempotency VALUES(7,'OTHER','key','digest',NULL,'2026-01-01 00:00:00')");
        assertEquals(1,transaction.cleanup());
        assertEquals(1L,count("api_idempotency")); assertEquals(1L,count("ticket_verification"));
        assertEquals(ErrorCode.TICKET_ALREADY_USED,assertThrows(TicketVerificationException.class,
                ()->service.verify(7,"Key",request("Code"))).getErrorCode());
    }

    @Test
    void refundHoldingOrderLockWinsAndVerificationSeesRefunding() throws Exception {
        var held=new CountDownLatch(1); var release=new CountDownLatch(1);
        try(var executor=Executors.newVirtualThreadPerTaskExecutor()) {
            var refund=executor.submit(()->other.execute(s->{
                jdbc.queryForObject("SELECT status FROM orders WHERE id=2 FOR UPDATE",Integer.class);
                assertEquals(0L,jdbc.queryForObject("SELECT COUNT(*) FROM ticket t JOIN order_item oi ON oi.id=t.order_item_id WHERE oi.order_id=2 AND t.status=1",Long.class));
                held.countDown(); await(release);
                jdbc.update("UPDATE orders SET status=4 WHERE id=2"); return null;
            }));
            assertTrue(held.await(5,TimeUnit.SECONDS));
            var verify=executor.submit(()->outcome("key"));
            release.countDown(); refund.get(10,TimeUnit.SECONDS);
            assertEquals(ErrorCode.ORDER_NOT_ADMISSIBLE,verify.get(10,TimeUnit.SECONDS));
        }
        assertEquals(0L,count("ticket_verification"));
    }

    @Test
    void verificationWinnerPreventsWholeOrderRefundAndBinaryScopeKeepsKeysDistinct() throws Exception {
        service.verify(7,"Key",request("Code"));
        Boolean accepted=other.execute(s->{
            jdbc.queryForObject("SELECT status FROM orders WHERE id=2 FOR UPDATE",Integer.class);
            long used=jdbc.queryForObject("SELECT COUNT(*) FROM ticket t JOIN order_item oi ON oi.id=t.order_item_id WHERE oi.order_id=2 AND t.status=1",Long.class);
            if(used>0) { return false; }
            jdbc.update("UPDATE orders SET status=4 WHERE id=2"); return true;
        });
        assertFalse(accepted);
        assertEquals(1,jdbc.queryForObject("SELECT status FROM orders WHERE id=2",Integer.class));
        jdbc.update("INSERT INTO api_idempotency VALUES(7,?,'key','digest',NULL,'2026-10-20 12:00:00')",TicketVerificationTransaction.OPERATION);
        jdbc.update("INSERT INTO api_idempotency VALUES(7,?,'Key','digest',NULL,'2026-10-20 12:00:00')",TicketVerificationTransaction.OPERATION.toUpperCase(java.util.Locale.ROOT));
        assertEquals(3L,count("api_idempotency"));
    }

    @Test
    void missingAssociationInAuditIsNotHidden() throws Exception {
        service.verify(7,"key",request("Code"));
        jdbc.update("DELETE FROM movie WHERE id=10");
        assertEquals(ErrorCode.INTERNAL_ERROR,assertThrows(TicketVerificationException.class,
                ()->service.list(new TicketVerificationQueryRequest())).getErrorCode());
    }

    private List<Object> concurrent(String first,String second) throws Exception {
        var barrier=new CyclicBarrier(2);
        try(var executor=Executors.newVirtualThreadPerTaskExecutor()) {
            var a=executor.submit(()->{barrier.await(5,TimeUnit.SECONDS);return outcome(first);});
            var b=executor.submit(()->{barrier.await(5,TimeUnit.SECONDS);return outcome(second);});
            return List.of(a.get(15,TimeUnit.SECONDS),b.get(15,TimeUnit.SECONDS));
        }
    }
    private Object outcome(String key) {
        try { return service.verify(7,key,request("Code")); }
        catch(TicketVerificationException e) { return e.getErrorCode(); }
    }
    private TicketVerificationRequest request(String code) {
        return json.readValue("{\"ticketCode\":\""+code+"\",\"screeningId\":\"3\"}",TicketVerificationRequest.class);
    }
    private long count(String table) { return jdbc.queryForObject("SELECT COUNT(*) FROM "+table,Long.class); }
    private static void await(CountDownLatch latch) {
        try { if(!latch.await(5,TimeUnit.SECONDS)) { throw new IllegalStateException("等待超时"); } }
        catch(InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
    }
}
