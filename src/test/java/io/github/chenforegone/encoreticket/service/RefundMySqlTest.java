package io.github.chenforegone.encoreticket.service;

import io.github.chenforegone.encoreticket.common.time.Clock;
import io.github.chenforegone.encoreticket.service.transaction.TicketVerificationTransaction;

import io.github.chenforegone.encoreticket.gateway.RefundGateway;
import io.github.chenforegone.encoreticket.gateway.SimulatedRefundGateway;
import io.github.chenforegone.encoreticket.common.exception.TicketVerificationException;
import io.github.chenforegone.encoreticket.common.exception.RefundException;

import io.github.chenforegone.encoreticket.service.impl.RefundServiceImpl;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import tools.jackson.databind.json.JsonMapper;
import java.net.URI;
import java.time.Instant;
import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 退款服务及并发的真实 MySQL 边界，只创建新的专用隔离库。
 * @author c1342
 */
@EnabledIfSystemProperty(named="refund.mysql.enabled", matches="true")
class RefundMySqlTest {
    static JdbcTemplate jdbc;
    static RefundServiceImpl service;
    static String unavailable;
    static final Instant NOW = Instant.parse("2026-10-20T05:00:00Z");

    @BeforeAll static void setup() throws Exception {
        String url = System.getProperty("refund.mysql.url", "");
        assertThat(url).startsWith("jdbc:mysql://");
        URI uri = URI.create(url.substring(5));
        assertThat(uri.getUserInfo()).isNull();
        assertThat(uri.getPath()).matches("/encore_ticket_refund_test_[A-Za-z0-9_]+");
        String password = System.getenv("REFUND_MYSQL_PASSWORD");
        if (password == null || password.isBlank()) { unavailable="未配置隔离测试密码"; return; }
        var ds = new DriverManagerDataSource(url, System.getProperty("refund.mysql.user", "encore_ticket"), password);
        try (var c = ds.getConnection()) {
            assertThat(c.getMetaData().getDatabaseProductName()).isEqualTo("MySQL");
        } catch (java.sql.SQLException e) {
            if (e.getSQLState()!=null && e.getSQLState().startsWith("08")) { unavailable="隔离 MySQL 不可达"; return; }
            throw e;
        }
        jdbc = new JdbcTemplate(ds);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE()", Long.class)).isZero();
        Flyway.configure().dataSource(ds).cleanDisabled(true).locations("classpath:db/migration").target("26").load().migrate();
        jdbc.update("INSERT INTO orders(id,order_no,user_id,screening_id,total_amount,expires_at,status,create_time,update_time) VALUES(900,'legacy-order',20,40,88,?,4,?,?)",utc(),utc(),utc());
        jdbc.update("INSERT INTO payment(id,payment_no,order_id,amount,status,attempt_id,create_time,update_time) VALUES(900,'legacy-payment',900,88,1,'legacy-attempt',?,?)",utc(),utc());
        jdbc.update("INSERT INTO refund(id,refund_no,order_id,payment_id,amount,status,create_time,update_time) VALUES(900,'legacy-refund',900,900,88,0,?,?)",utc(),utc());
        Flyway.configure().dataSource(ds).cleanDisabled(true).locations("classpath:db/migration").load().migrate();
        assertThat(jdbc.queryForObject("SELECT source FROM refund WHERE id=900",String.class)).isEqualTo("LEGACY");
        assertThat(jdbc.queryForObject("SELECT submitted FROM refund_attempt WHERE refund_id=900",Boolean.class)).isTrue();
        assertThat(jdbc.queryForObject("SELECT operation FROM refund_audit WHERE refund_id=900",String.class)).isEqualTo("LEGACY_IMPORTED");
        service = new RefundServiceImpl(new io.github.chenforegone.encoreticket.mapper.RefundProcessingMapper(jdbc),new DataSourceTransactionManager(ds),()->NOW,JsonMapper.builder().build(),
                new SimulatedRefundGateway(jdbc,()->NOW));
    }
    @BeforeEach void fixture() {
        assumeTrue(unavailable==null,unavailable);
        for (String table : java.util.List.of("refund_simulated_result","refund_audit","refund_attempt","api_idempotency","refund","ticket_verification","ticket","payment_audit","payment_notification","payment","order_item","screening_seat","orders","screening")) jdbc.update("DELETE FROM "+table);
        jdbc.update("INSERT INTO screening(id,price,movie_id,hall_id,start_time,admission_open_time,end_time,duration_minutes_snapshot,turnaround_minutes_snapshot,sale_start_time,sale_end_time,status,create_time,update_time) VALUES(40,44,1,1,'2026-10-20 15:00:00','2026-10-20 12:00:00','2026-10-20 17:00:00',120,15,'2026-10-19 10:00:00','2026-10-20 14:45:00',1,?,?)",utc(),utc());
        jdbc.update("INSERT INTO orders(id,order_no,user_id,screening_id,total_amount,expires_at,status,version,create_time,update_time) VALUES(10,'O10',20,40,88,?,1,0,?,?)",utc(),utc(),utc());
        jdbc.update("INSERT INTO payment(id,payment_no,order_id,amount,currency,status,version,attempt_id,create_time,update_time) VALUES(30,'P30',10,88,'CNY',1,0,'pay-1',?,?)",utc(),utc());
        for (int n=1;n<=2;n++) {
            jdbc.update("INSERT INTO screening_seat(id,seat_id,screening_id,status,order_id,create_time,update_time) VALUES(?,?,40,2,10,?,?)",100+n,n,utc(),utc());
            jdbc.update("INSERT INTO order_item(id,order_id,screening_seat_id,unit_price,create_time,update_time) VALUES(?,10,?,44,?,?)",200+n,100+n,utc(),utc());
            jdbc.update("INSERT INTO ticket(id,status,order_item_id,ticket_code,create_time,update_time) VALUES(?,0,?,?,?,?)",300+n,200+n,"T"+n,utc(),utc());
        }
    }
    @Test void failedRefundCanOnlyBeRetriedByExplicitAdminAction() {
        var accepted=service.apply(10L,20L,"apply-failed");
        var failedGateway=new RefundGateway() {
            public Result query(String r,int a,String p,java.math.BigDecimal m) { return Result.FAILED; }
            public Result submit(String r,int a,String p,java.math.BigDecimal m) { return Result.FAILED; }
        };
        var failing=new RefundServiceImpl(new io.github.chenforegone.encoreticket.mapper.RefundProcessingMapper(jdbc),new DataSourceTransactionManager(jdbc.getDataSource()),()->NOW,JsonMapper.builder().build(),failedGateway);
        failing.runPending(10);
        assertThat(service.byOrder(10L,20L).items().getFirst().status()).isEqualTo("FAILED");
        service.adminProcess(Long.valueOf(accepted.refundId()),99L,"retry-1","RETRY");
        service.runPending(10);
        assertThat(service.byOrder(10L,20L).orderStatus()).isEqualTo("REFUNDED");
    }

    @Test void invalidClaimsBackOffWithoutStarvingTheNextScanOrChangingMoneyState() {
        var accepted=service.apply(10L,20L,"healthy-after-invalid");
        for (int n=1;n<=100;n++) {
            long order=1000+n;
            jdbc.update("INSERT INTO orders(id,order_no,user_id,screening_id,total_amount,expires_at,status,create_time,update_time) VALUES(?,?,20,40,88,?,4,?,?)",order,"bad-"+n,utc(),utc(),utc());
            jdbc.update("INSERT INTO refund(id,refund_no,order_id,payment_id,amount,status,source,create_time,update_time) VALUES(?,?,?,?,88,0,'USER',?,?)",n,"bad-refund-"+n,order,order,utc(),utc());
        }
        assertThat(service.runPending(100)).isZero();
        assertThat(service.runPending(100)).isEqualTo(1);
        assertThat(service.byOrder(10L,20L).orderStatus()).isEqualTo("REFUNDED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM refund WHERE id<=100 AND status=0 AND lease_token IS NULL AND next_run_at>? AND next_run_at<=?",Integer.class,utc(),utc().plusHours(1))).isEqualTo(100);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM orders WHERE id>1000 AND status=4",Integer.class)).isEqualTo(100);
        assertThat(service.adminDetail(Long.valueOf(accepted.refundId())).attempts()).hasSize(1);
    }

    @Test void cutoffEqualityAndUsedTicketRejectNewApplication() {
        jdbc.update("UPDATE screening SET start_time='2026-10-20 13:15:00',sale_end_time='2026-10-20 13:00:00',end_time='2026-10-20 15:15:00' WHERE id=40");
        assertThatThrownBy(()->service.apply(10L,20L,"at-cutoff")).isInstanceOf(RefundException.class)
                .extracting(e->((RefundException)e).getErrorCode().name()).isEqualTo("REFUND_CUTOFF_REACHED");
        jdbc.update("UPDATE ticket SET status=1 WHERE id=301");
        assertThatThrownBy(()->service.apply(10L,20L,"used")).isInstanceOf(RefundException.class)
                .extracting(e->((RefundException)e).getErrorCode().name()).isEqualTo("TICKET_ALREADY_USED");
    }
    @Test void differentKeysConcurrentlyCreateOneRefundAndOneChannelFact() throws Exception {
        try(var pool=java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var start=new java.util.concurrent.CountDownLatch(1);
            var a=pool.submit(()->{start.await();return service.apply(10L,20L,"parallel-a");});
            var b=pool.submit(()->{start.await();return service.apply(10L,20L,"parallel-b");});
            start.countDown();
            assertThat(a.get(10,java.util.concurrent.TimeUnit.SECONDS).refundId()).isEqualTo(b.get(10,java.util.concurrent.TimeUnit.SECONDS).refundId());
            var x=pool.submit(()->service.runPending(10)); var y=pool.submit(()->service.runPending(10));
            x.get(10,java.util.concurrent.TimeUnit.SECONDS);y.get(10,java.util.concurrent.TimeUnit.SECONDS);
        }
        assertThat(service.byOrder(10L,20L).items()).hasSize(1);
        assertThat(service.byOrder(10L,20L).orderStatus()).isEqualTo("REFUNDED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM refund_simulated_result",Integer.class)).isEqualTo(1);
    }
    @Test void unknownDoesNotStartAnotherAttemptAndRestartCanReconcile() {
        var accepted=service.apply(10L,20L,"unknown");
        var real=new SimulatedRefundGateway(jdbc,()->NOW);
        var unknown=new RefundGateway() {
            public Result query(String r,int a,String p,java.math.BigDecimal m) { return real.query(r,a,p,m); }
            public Result submit(String r,int a,String p,java.math.BigDecimal m) { real.submit(r,a,p,m); throw new IllegalStateException("模拟响应丢失"); }
        };
        var restarted=new RefundServiceImpl(new io.github.chenforegone.encoreticket.mapper.RefundProcessingMapper(jdbc),new DataSourceTransactionManager(jdbc.getDataSource()),()->NOW,JsonMapper.builder().build(),unknown);
        restarted.runPending(10);
        assertThat(service.byOrder(10L,20L).items().getFirst().status()).isEqualTo("PENDING");
        assertThatThrownBy(()->service.adminProcess(Long.valueOf(accepted.refundId()),99L,"wrong-retry","RETRY")).isInstanceOf(RefundException.class);
        service.adminProcess(Long.valueOf(accepted.refundId()),99L,"check","CHECK");
        service.runPending(10);
        assertThat(service.byOrder(10L,20L).orderStatus()).isEqualTo("REFUNDED");
        assertThat(service.adminDetail(Long.valueOf(accepted.refundId())).attempts()).hasSize(1);
    }
    @Test void latePaymentWithoutTicketsDoesNotReleaseOtherOrdersSeats() {
        jdbc.update("DELETE FROM ticket");
        jdbc.update("UPDATE orders SET status=4 WHERE id=10");
        jdbc.update("UPDATE screening_seat SET order_id=999,status=2 WHERE order_id=10");
        jdbc.update("INSERT INTO refund(id,refund_no,order_id,payment_id,amount,status,source,next_run_at,create_time,update_time) VALUES(50,'R50',10,30,88,0,'LATE_PAYMENT',?,?,?)",utc(),utc(),utc());
        service.runPending(10);
        assertThat(service.byOrder(10L,20L).orderStatus()).isEqualTo("REFUNDED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM screening_seat WHERE order_id=999 AND status=2",Integer.class)).isEqualTo(2);
    }

    @Test void acceptanceAndSnapshotRollbackTogetherWhenAuditFails() {
        jdbc.execute("ALTER TABLE refund_audit ADD CONSTRAINT ck_refund_test_fault CHECK (operation<>'USER_ACCEPTED')");
        try {
            assertThatThrownBy(()->service.apply(10L,20L,"rollback")).isInstanceOf(org.springframework.dao.DataAccessException.class);
            assertThat(service.byOrder(10L,20L).orderStatus()).isEqualTo("PAID");
            assertThat(service.byOrder(10L,20L).items()).isEmpty();
        } finally { jdbc.execute("ALTER TABLE refund_audit DROP CHECK ck_refund_test_fault"); }
        assertThat(service.apply(10L,20L,"rollback").status()).isEqualTo("PENDING");
    }
    @Test void cutoffIsCheckedAfterWaitingForOrderLock() throws Exception {
        var manager=new DataSourceTransactionManager(jdbc.getDataSource());
        var transaction=new org.springframework.transaction.support.TransactionTemplate(manager);
        var held=new java.util.concurrent.CountDownLatch(1);
        var release=new java.util.concurrent.CountDownLatch(1);
        var time=new java.util.concurrent.atomic.AtomicReference<>(NOW);
        var waiting=new RefundServiceImpl(new io.github.chenforegone.encoreticket.mapper.RefundProcessingMapper(jdbc),manager,time::get,JsonMapper.builder().build(),new SimulatedRefundGateway(jdbc,()->NOW));
        try(var pool=java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var lock=pool.submit(()->transaction.execute(s->{
                jdbc.queryForObject("SELECT id FROM screening WHERE id=40 FOR UPDATE",Long.class);
                jdbc.queryForObject("SELECT id FROM orders WHERE id=10 FOR UPDATE",Long.class);
                held.countDown();
                try { assertThat(release.await(10,java.util.concurrent.TimeUnit.SECONDS)).isTrue(); } catch(InterruptedException e){throw new RuntimeException(e);}
                return true;
            }));
            assertThat(held.await(10,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            var application=pool.submit(()->waiting.apply(10L,20L,"wait-cutoff"));
            time.set(Instant.parse("2026-10-20T06:45:00Z")); release.countDown(); lock.get();
            assertThatThrownBy(()->application.get(10,java.util.concurrent.TimeUnit.SECONDS)).hasCauseInstanceOf(RefundException.class);
        } finally { release.countDown(); }
        assertThat(service.byOrder(10L,20L).items()).isEmpty();
    }
    @Test void usedTicketCommittedUnderCommonLocksWinsAgainstRefund() throws Exception {
        var transaction=new org.springframework.transaction.support.TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource()));
        var held=new java.util.concurrent.CountDownLatch(1); var release=new java.util.concurrent.CountDownLatch(1);
        try(var pool=java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var verification=pool.submit(()->transaction.execute(s->{
                jdbc.queryForObject("SELECT id FROM screening WHERE id=40 FOR UPDATE",Long.class);
                jdbc.queryForObject("SELECT id FROM orders WHERE id=10 FOR UPDATE",Long.class);
                jdbc.queryForObject("SELECT id FROM ticket WHERE id=301 FOR UPDATE",Long.class);
                held.countDown();
                try { assertThat(release.await(10,java.util.concurrent.TimeUnit.SECONDS)).isTrue(); } catch(InterruptedException e){throw new RuntimeException(e);}
                jdbc.update("UPDATE ticket SET status=1 WHERE id=301"); return true;
            }));
            assertThat(held.await(10,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            var application=pool.submit(()->service.apply(10L,20L,"compete"));
            release.countDown(); verification.get();
            assertThatThrownBy(()->application.get(10,java.util.concurrent.TimeUnit.SECONDS)).hasCauseInstanceOf(RefundException.class);
        } finally { release.countDown(); }
        assertThat(service.byOrder(10L,20L).items()).isEmpty();
    }
    @Test void legacyFailedAllowsAnExplicitlyAuthorizedNewAttempt() {
        jdbc.update("UPDATE orders SET status=4 WHERE id=10");
        jdbc.update("INSERT INTO refund(id,refund_no,order_id,payment_id,amount,status,source,create_time,update_time) VALUES(50,'R50',10,30,88,2,'LEGACY',?,?)",utc(),utc());
        jdbc.update("INSERT INTO refund_attempt(refund_id,attempt_no,status,submitted,create_time,update_time) VALUES(50,1,'FAILED',TRUE,?,?)",utc(),utc());
        var accepted=service.adminProcess(50L,99L,"legacy-retry","RETRY");
        assertThat(accepted.source()).isEqualTo("LEGACY");
        service.runPending(10);
        assertThat(service.byOrder(10L,20L).orderStatus()).isEqualTo("REFUNDED");
        assertThat(service.adminDetail(50L).attempts()).extracting(a->a.status()).containsExactly("FAILED","SUCCESS");
        assertThat(service.adminProcess(50L,99L,"legacy-retry","RETRY")).isEqualTo(accepted);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM refund_simulated_result WHERE attempt_no=2",Integer.class)).isEqualTo(1);
    }

    @Test void legacyPendingDoesNotIssueAnUnprovenSecondRefund() {
        jdbc.update("UPDATE orders SET status=4 WHERE id=10");
        jdbc.update("INSERT INTO refund(id,refund_no,order_id,payment_id,amount,status,source,create_time,update_time) VALUES(50,'R50',10,30,88,0,'LEGACY',?,?)",utc(),utc());
        service.runPending(10);
        assertThat(service.byOrder(10L,20L).items().getFirst().status()).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM refund_simulated_result",Integer.class)).isZero();
    }

    @Test void sameKeyConflictAndExpiredSnapshotsDoNotRemoveBusinessUniqueness() {
        var first=service.apply(10L,20L,"stable");
        assertThatThrownBy(()->service.adminProcess(Long.valueOf(first.refundId()),99L,"same-admin","RETRY")).isInstanceOf(RefundException.class);
        jdbc.update("INSERT INTO api_idempotency(operator_id,operation,idempotency_key,request_digest,response_json,expires_at) VALUES(20,'ticket-verification:v1','keep','x','{}',?)",utc());
        jdbc.update("UPDATE api_idempotency SET expires_at=? WHERE operation='refund-apply:v1'",utc());
        assertThat(service.cleanup()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM api_idempotency WHERE operation='ticket-verification:v1'",Integer.class)).isEqualTo(1);
        assertThat(service.apply(10L,20L,"stable").refundId()).isEqualTo(first.refundId());
    }
    @Test void channelSuccessSurvivesLocalFailureAndRecoversWithoutAnotherRefund() {
        var first=service.apply(10L,20L,"local-fault");
        jdbc.execute("ALTER TABLE refund_audit ADD CONSTRAINT ck_refund_test_result_fault CHECK (operation<>'CHANNEL_SUCCESS')");
        try {
            service.runPending(10);
            assertThat(service.byOrder(10L,20L).orderStatus()).isEqualTo("REFUNDING");
            assertThat(service.byOrder(10L,20L).items().getFirst().status()).isEqualTo("PENDING");
        } finally { jdbc.execute("ALTER TABLE refund_audit DROP CHECK ck_refund_test_result_fault"); }
        jdbc.update("UPDATE refund SET lease_until=?,next_run_at=?",utc(),utc());
        service.runPending(10);
        assertThat(service.byOrder(10L,20L).orderStatus()).isEqualTo("REFUNDED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM refund_simulated_result",Integer.class)).isEqualTo(1);
        assertThat(service.adminDetail(Long.valueOf(first.refundId())).attempts()).hasSize(1);
    }

    static java.time.LocalDateTime utc() { return java.time.LocalDateTime.ofInstant(NOW,java.time.ZoneOffset.UTC); }
    @Test void channelExceptionsKeepPendingAndLogOnlyBoundedSafeDiagnostics() {
        var accepted=service.apply(10L,20L,"safe-diagnostic");
        var logger=(ch.qos.logback.classic.Logger)org.slf4j.LoggerFactory.getLogger(RefundServiceImpl.class);
        var logs=new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        logs.start(); logger.addAppender(logs);
        var gateway=new RefundGateway() {
            public Result query(String r,int a,String p,java.math.BigDecimal m) { throw new IllegalStateException("secret-token-password",new IllegalArgumentException("secret-account")); }
            public Result submit(String r,int a,String p,java.math.BigDecimal m) { throw new AssertionError("未知时不能提交"); }
        };
        try {
            var failing=new RefundServiceImpl(new io.github.chenforegone.encoreticket.mapper.RefundProcessingMapper(jdbc),new DataSourceTransactionManager(jdbc.getDataSource()),()->NOW,JsonMapper.builder().build(),gateway);
            assertThat(failing.runPending(10)).isEqualTo(1);
            assertThat(service.byOrder(10L,20L).items().getFirst().status()).isEqualTo("PENDING");
            assertThat(logs.list).hasSize(1);
            var event=logs.list.getFirst();
            assertThat(event.getThrowableProxy()).isNull();
            assertThat(event.getFormattedMessage()).contains("refundId="+accepted.refundId(),"attemptId=1","IllegalStateException","IllegalArgumentException")
                    .doesNotContain("secret-token-password","secret-account");
            assertThat(event.getFormattedMessage().length()).isLessThan(17000);
        } finally { logger.detachAppender(logs); logs.stop(); }
    }

    @Test void expiredLeaseReentryAndLateResultReplayDoNotDuplicateChannelMoney() throws Exception {
        var accepted=service.apply(10L,20L,"lease-reentry");
        long id=Long.parseLong(accepted.refundId());
        var time=new java.util.concurrent.atomic.AtomicReference<>(NOW);
        var entered=new java.util.concurrent.CountDownLatch(1);
        var release=new java.util.concurrent.CountDownLatch(1);
        var real=new SimulatedRefundGateway(jdbc,()->NOW);
        var delayed=new RefundGateway() {
            public Result query(String r,int a,String p,java.math.BigDecimal m) { return real.query(r,a,p,m); }
            public Result submit(String r,int a,String p,java.math.BigDecimal m) {
                Result result=real.submit(r,a,p,m); entered.countDown();
                try { assertThat(release.await(10,java.util.concurrent.TimeUnit.SECONDS)).isTrue(); }
                catch(InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
                return result;
            }
        };
        var first=new RefundServiceImpl(new io.github.chenforegone.encoreticket.mapper.RefundProcessingMapper(jdbc),new DataSourceTransactionManager(jdbc.getDataSource()),time::get,JsonMapper.builder().build(),delayed);
        var recovery=new RefundServiceImpl(new io.github.chenforegone.encoreticket.mapper.RefundProcessingMapper(jdbc),new DataSourceTransactionManager(jdbc.getDataSource()),time::get,JsonMapper.builder().build(),real);
        try(var pool=java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var original=pool.submit(()->first.processOne(id));
            try {
                assertThat(entered.await(10,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
                assertThat(recovery.processOne(id)).isFalse();
                time.set(NOW.plusSeconds(61));
                assertThat(recovery.runPending(10)).isEqualTo(1);
                assertThat(service.byOrder(10L,20L).orderStatus()).isEqualTo("REFUNDED");
            } finally { release.countDown(); }
            assertThat(original.get(10,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        } finally { release.countDown(); }
        assertThat(recovery.processOne(id)).isFalse();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM refund_simulated_result",Integer.class)).isEqualTo(1);
        assertThat(service.adminDetail(id).audits()).filteredOn(a->a.operation().equals("CHANNEL_SUCCESS")).hasSize(1);
        assertThat(service.adminDetail(id).attempts()).hasSize(1);
    }

    @Test void realVerificationWinningTheLockRejectsRefund() throws Exception { competeWithVerification(true); }
    @Test void realRefundWinningTheLockRejectsVerification() throws Exception { competeWithVerification(false); }

    private void competeWithVerification(boolean verificationWins) throws Exception {
        jdbc.update("INSERT INTO sys_user(id,username,password,status,create_time,update_time) VALUES(99,'refund-staff','unused',0,?,?) ON DUPLICATE KEY UPDATE status=0",utc(),utc());
        jdbc.update("INSERT INTO movie(id,title,release_date,duration_minutes,status,create_time,update_time) VALUES(1,'退款竞争','2026-10-20',120,0,?,?) ON DUPLICATE KEY UPDATE title=title",utc(),utc());
        jdbc.update("INSERT INTO hall(id,hall_name,hall_no,status,create_time,update_time) VALUES(1,'测试厅','refund-test',0,?,?) ON DUPLICATE KEY UPDATE hall_name=hall_name",utc(),utc());
        for(int n=1;n<=2;n++) jdbc.update("INSERT INTO seat(id,hall_id,`row_number`,seat_number,status,create_time,update_time) VALUES(?,1,1,?,0,?,?) ON DUPLICATE KEY UPDATE status=0",n,n,utc(),utc());
        var held=new java.util.concurrent.CountDownLatch(1); var release=new java.util.concurrent.CountDownLatch(1);
        var started=new java.util.concurrent.CountDownLatch(1);
        var blockingClock=(Clock)()->{
            if(Thread.currentThread().getName().equals("refund-lock-winner")) {
                held.countDown();
                try { assertThat(release.await(10,java.util.concurrent.TimeUnit.SECONDS)).isTrue(); }
                catch(InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
            }
            return NOW;
        };
        var manager=new DataSourceTransactionManager(jdbc.getDataSource());
        var refundMapper=new io.github.chenforegone.encoreticket.mapper.RefundProcessingMapper(jdbc) {
            @Override public boolean lockScreening(long id) {
                boolean locked=super.lockScreening(id);
                if(!verificationWins) blockingClock.instant();
                return locked;
            }
        };
        var refunds=new RefundServiceImpl(refundMapper,manager,()->NOW,JsonMapper.builder().build(),new SimulatedRefundGateway(jdbc,()->NOW));
        var mapper=new io.github.chenforegone.encoreticket.mapper.TicketVerificationMapper(jdbc) {
            @Override public Screening lockScreening(long id) {
                var locked=super.lockScreening(id);
                if(verificationWins) blockingClock.instant();
                return locked;
            }
        };
        var converter=new io.github.chenforegone.encoreticket.converter.TicketVerificationConverter();
        var transaction=new TicketVerificationTransaction(mapper,converter,()->NOW,JsonMapper.builder().build(),manager);
        var verification=new io.github.chenforegone.encoreticket.service.impl.TicketVerificationServiceImpl(transaction,mapper,converter);
        var request=JsonMapper.builder().build().readValue("{\"ticketCode\":\"T1\",\"screeningId\":\"40\"}",io.github.chenforegone.encoreticket.model.dto.TicketVerificationRequest.class);
        java.util.concurrent.Callable<Object> refund=()->refunds.apply(10L,20L,"real-refund-race");
        java.util.concurrent.Callable<Object> verify=()->verification.verify(99L,"real-verification-race",request);
        try(var pool=java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var winner=pool.submit(()->{ Thread.currentThread().setName("refund-lock-winner"); return (verificationWins?verify:refund).call(); });
            try {
                assertThat(held.await(10,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
                var loser=pool.submit(()->{ started.countDown(); return (verificationWins?refund:verify).call(); });
                assertThat(started.await(10,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
                release.countDown();
                assertThat(winner.get(10,java.util.concurrent.TimeUnit.SECONDS)).isNotNull();
                assertThatThrownBy(()->loser.get(10,java.util.concurrent.TimeUnit.SECONDS))
                        .hasCauseInstanceOf(verificationWins?RefundException.class:TicketVerificationException.class)
                        .satisfies(e->assertThat(((io.github.chenforegone.encoreticket.common.exception.ApplicationException)e.getCause()).getErrorCode().name())
                                .isEqualTo(verificationWins?"TICKET_ALREADY_USED":"ORDER_NOT_ADMISSIBLE"));
            } finally { release.countDown(); }
        } finally { release.countDown(); }
        assertThat(service.byOrder(10L,20L).orderStatus()).isEqualTo(verificationWins?"PAID":"REFUNDING");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ticket_verification",Integer.class)).isEqualTo(verificationWins?1:0);
        assertThat(service.byOrder(10L,20L).items()).hasSize(verificationWins?0:1);
    }

    @Test void simulatedChannelUsesTheInjectedClock() {
        service.apply(10L,20L,"channel-clock");
        service.runPending(10);
        assertThat(jdbc.queryForObject("SELECT create_time FROM refund_simulated_result",java.time.LocalDateTime.class)).isEqualTo(utc());
    }

    @Test void fullRefundCanBeAcceptedQueriedAndCompleted() {
        var accepted=service.apply(10L,20L,"apply-1");
        assertThat(accepted.amount()).isEqualTo("88.00");
        assertThat(accepted.status()).isEqualTo("PENDING");
        service.runPending(10);
        var result=service.byOrder(10L,20L);
        assertThat(result.orderStatus()).isEqualTo("REFUNDED");
        assertThat(result.items().getFirst().status()).isEqualTo("SUCCESS");
        assertThat(service.apply(10L,20L,"apply-1")).isEqualTo(accepted);
    }
}
