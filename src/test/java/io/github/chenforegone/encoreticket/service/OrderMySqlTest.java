package io.github.chenforegone.encoreticket.service;

import io.github.chenforegone.encoreticket.repository.PaymentSettlementRepository;
import io.github.chenforegone.encoreticket.gateway.PaymentChannelEvent;
import io.github.chenforegone.encoreticket.gateway.SimulatedRefundGateway;
import io.github.chenforegone.encoreticket.mapper.OrderWorkflowMapper;
import io.github.chenforegone.encoreticket.mapper.PaymentIdempotencyMapper;
import io.github.chenforegone.encoreticket.model.dto.CreateOrderRequest;
import io.github.chenforegone.encoreticket.service.impl.OrderServiceImpl;
import io.github.chenforegone.encoreticket.service.impl.PaymentServiceImpl;
import io.github.chenforegone.encoreticket.service.impl.RefundServiceImpl;
import io.github.chenforegone.encoreticket.service.impl.TicketVerificationServiceImpl;
import io.github.chenforegone.encoreticket.service.transaction.TicketVerificationTransaction;
import io.github.chenforegone.encoreticket.converter.TicketVerificationConverter;
import io.github.chenforegone.encoreticket.mapper.RefundProcessingMapper;
import io.github.chenforegone.encoreticket.mapper.TicketVerificationMapper;
import io.github.chenforegone.encoreticket.gateway.RefundGateway;
import io.github.chenforegone.encoreticket.model.dto.TicketVerificationRequest;
import io.github.chenforegone.encoreticket.model.vo.PaymentVO;
import io.github.chenforegone.encoreticket.common.exception.ApplicationException;
import io.github.chenforegone.encoreticket.common.exception.RefundException;
import io.github.chenforegone.encoreticket.common.exception.TicketVerificationException;
import java.math.BigDecimal;
import java.util.concurrent.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import tools.jackson.databind.json.JsonMapper;
import java.time.*;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import static org.assertj.core.api.Assertions.*;

/**
 * 订单公开服务入口的真实 MySQL 测试，仅操作本次创建的隔离库。
 * @author c1342
 */
@EnabledIfSystemProperty(named="order.mysql.enabled", matches="true")
class OrderMySqlTest {
    static JdbcTemplate jdbc;
    static com.zaxxer.hikari.HikariDataSource datasource;
    static OrderService service;
    static final Instant NOW=Instant.parse("2026-10-20T05:00:00Z");
    static final AtomicReference<Instant> time=new AtomicReference<>(NOW);
    static String unavailable;
    static final String JDBC_OPTIONS="?connectionTimeZone=UTC&connectTimeout=3000&socketTimeout=8000"
            +"&sessionVariables=innodb_lock_wait_timeout=5,lock_wait_timeout=5";

    /**
     * 所有并发任务均可取消，关闭时不调用 ExecutorService 的无界 close。
     * @author c1342
     */
    private static final class TestTasks implements AutoCloseable {
        private final ExecutorService pool=Executors.newFixedThreadPool(2,r->{
            var thread=new Thread(r,"order-mysql-test");
            thread.setDaemon(true);
            return thread;
        });
        private final java.util.ArrayList<Future<?>> tasks=new java.util.ArrayList<>();
        <T> Future<T> submit(Callable<T> task) {
            Future<T> future=pool.submit(task);
            tasks.add(future);
            return future;
        }
        @Override public void close() {
            tasks.forEach(task->task.cancel(true));
            pool.shutdownNow();
            try {
                boolean terminated=pool.awaitTermination(10,TimeUnit.SECONDS);
                if(!terminated) unavailable="并发任务未在清理期限内退出，禁止复用夹具";
                assertThat(terminated).as("并发任务须在有界期限内退出").isTrue();
            } catch(InterruptedException e) {
                unavailable="任务清理被中断，禁止复用夹具";
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }
    }

    @BeforeAll static void setup() throws Exception {
        String password=System.getenv("ORDER_MYSQL_PASSWORD");
        if(password==null || password.isBlank()) { unavailable="未配置隔离 MySQL 密码"; return; }
        String server=System.getProperty("order.mysql.server","jdbc:mysql://127.0.0.1:3308/");
        assertThat(server).matches("jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/");
        String database="encore_ticket_order_test_"+UUID.randomUUID().toString().replace("-", "");
        var admin=new DriverManagerDataSource(server+JDBC_OPTIONS, "root", password);
        try(var connection=admin.getConnection()) {
            assertThat(connection.getMetaData().getDatabaseProductName()).isEqualTo("MySQL");
            try(var statement=connection.createStatement()) { statement.execute("CREATE DATABASE "+database); }
        } catch(java.sql.SQLException e) {
            if(e.getSQLState()!=null&&e.getSQLState().startsWith("08")) { unavailable="隔离 MySQL 不可达"; return; }
            throw e;
        }
        var ds=new com.zaxxer.hikari.HikariDataSource();
        ds.setJdbcUrl(server+database+JDBC_OPTIONS);
        ds.setConnectionTimeout(3000);
        ds.setValidationTimeout(2000);
        ds.setUsername("root");
        ds.setPassword(password);
        ds.setMaximumPoolSize(6);
        ds.setMinimumIdle(1);
        datasource=ds;
        jdbc=new JdbcTemplate(ds);
        Flyway.configure().dataSource(ds).cleanDisabled(true).locations("classpath:db/migration").load().migrate();
        service=new OrderServiceImpl(new OrderWorkflowMapper(jdbc),new PaymentIdempotencyMapper(jdbc),
                new DataSourceTransactionManager(ds), time::get, JsonMapper.builder().build());
    }

    @AfterAll static void closePool() {
        if(datasource!=null) datasource.close();
    }

    @BeforeEach void fixture() {
        org.junit.jupiter.api.Assumptions.assumeTrue(unavailable==null,unavailable);
        time.set(NOW);
        for(String table:List.of("refund_simulated_result","refund_audit","refund_attempt","refund","ticket_verification","ticket","payment_notification","payment_audit","payment","api_idempotency","order_item","screening_seat","orders","seat","screening","movie","hall","sys_user")) jdbc.update("DELETE FROM "+table);
        jdbc.update("INSERT INTO sys_user(id,username,password,status,create_time,update_time) VALUES(20,'order-user','unused',0,?,?)",utc(),utc());
        jdbc.update("INSERT INTO movie(id,title,release_date,duration_minutes,status,create_time,update_time) VALUES(1,'订单测试','2026-10-20',120,0,?,?)",utc(),utc());
        jdbc.update("INSERT INTO hall(id,hall_name,hall_no,status,create_time,update_time) VALUES(1,'订单测试厅','order-test',0,?,?)",utc(),utc());
        jdbc.update("INSERT INTO screening(id,price,movie_id,hall_id,start_time,admission_open_time,end_time,duration_minutes_snapshot,turnaround_minutes_snapshot,sale_start_time,sale_end_time,status,create_time,update_time) VALUES(40,39.90,1,1,'2026-10-22 15:00:00','2026-10-22 14:45:00','2026-10-22 17:00:00',120,15,'2026-10-19 10:00:00','2026-10-22 14:45:00',1,?,?)",utc(),utc());
        for(int n=1;n<=8;n++) {
            jdbc.update("INSERT INTO seat(id,hall_id,`row_number`,seat_number,status,create_time,update_time) VALUES(?,1,1,?,0,?,?)",n,n,utc(),utc());
            jdbc.update("INSERT INTO screening_seat(id,seat_id,screening_id,status,create_time,update_time) VALUES(?,?,40,0,?,?)",100+n,n,utc(),utc());
        }
    }
    static LocalDateTime utc() { return LocalDateTime.ofInstant(time.get(),ZoneOffset.UTC); }
    static CreateOrderRequest request(String... seats) { return new CreateOrderRequest("40",List.of(seats)); }

    @Test void seatDisableWinningTheSharedHallLockPreventsPurchase() throws Exception {
        var tx=new org.springframework.transaction.support.TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource()));
        var locked=new java.util.concurrent.CountDownLatch(1);
        var release=new java.util.concurrent.CountDownLatch(1);
        try(var pool=new TestTasks()) {
            var disable=pool.submit(()->tx.execute(s->{
                jdbc.queryForObject("SELECT id FROM hall WHERE id=1 FOR UPDATE",Long.class);
                jdbc.update("UPDATE seat SET status=1 WHERE id=1"); locked.countDown();
                awaitRelease(release);
                return true;
            }));
            try {
                assertThat(locked.await(10,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
                var purchase=pool.submit(()->service.create(20L,"disable-race",request("101","102")));
                release.countDown(); disable.get(10,java.util.concurrent.TimeUnit.SECONDS);
                assertThatThrownBy(()->purchase.get(10,java.util.concurrent.TimeUnit.SECONDS)).hasCauseInstanceOf(io.github.chenforegone.encoreticket.common.exception.ApplicationException.class);
            } finally { release.countDown(); }
        }
        assertThat(service.create(20L,"still-free",request("102")).seatCount()).isEqualTo(1);
    }

    @Test void racingForTheSameSeatNeverPartiallyHoldsTheLosingOrder() throws Exception {
        jdbc.update("INSERT INTO sys_user(id,username,password,status,create_time,update_time) VALUES(21,'order-other','unused',0,?,?)",utc(),utc());
        try(var pool=new TestTasks()) {
            var start=new java.util.concurrent.CountDownLatch(1);
            var a=pool.submit(()->{awaitRelease(start);try{return service.create(20L,"race-a",request("101","102")).orderId();}catch(io.github.chenforegone.encoreticket.common.exception.ConflictException e){return "conflict";}});
            var b=pool.submit(()->{awaitRelease(start);try{return service.create(21L,"race-b",request("102","103")).orderId();}catch(io.github.chenforegone.encoreticket.common.exception.ConflictException e){return "conflict";}});
            start.countDown();
            var results=List.of(a.get(10,java.util.concurrent.TimeUnit.SECONDS),b.get(10,java.util.concurrent.TimeUnit.SECONDS));
            assertThat(results).filteredOn("conflict"::equals).hasSize(1);
            assertThat(service.list(20L,null).total()+service.list(21L,null).total()).isEqualTo(1);
        }
    }

    private PaymentServiceImpl payments() throws Exception {
        var configuration=new com.baomidou.mybatisplus.core.MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.setEnvironment(new org.apache.ibatis.mapping.Environment("orders",new org.mybatis.spring.transaction.SpringManagedTransactionFactory(),jdbc.getDataSource()));
        String resource="mapper/OrderMapper.xml";
        try(var stream=org.apache.ibatis.io.Resources.getResourceAsStream(resource)) {
            new org.apache.ibatis.builder.xml.XMLMapperBuilder(stream,configuration,resource,configuration.getSqlFragments()).parse();
        }
        String paymentResource="mapper/PaymentSettlementMapper.xml";
        try(var stream=org.apache.ibatis.io.Resources.getResourceAsStream(paymentResource)) {
            new org.apache.ibatis.builder.xml.XMLMapperBuilder(stream,configuration,paymentResource,configuration.getSqlFragments()).parse();
        }
        configuration.addMapper(io.github.chenforegone.encoreticket.mapper.PaymentSettlementMapper.class);
        configuration.addMapper(io.github.chenforegone.encoreticket.mapper.PaymentMapper.class);
        configuration.addMapper(io.github.chenforegone.encoreticket.mapper.PaymentAuditMapper.class);
        configuration.addMapper(io.github.chenforegone.encoreticket.mapper.RefundMapper.class);
        var session=new org.mybatis.spring.SqlSessionTemplate(new com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder().build(configuration));
        var manager=new DataSourceTransactionManager(jdbc.getDataSource());
        var paymentTarget=new io.github.chenforegone.encoreticket.service.impl.PaymentServiceImpl(
                session.getMapper(io.github.chenforegone.encoreticket.mapper.PaymentMapper.class),session.getMapper(io.github.chenforegone.encoreticket.mapper.OrderMapper.class),
                session.getMapper(io.github.chenforegone.encoreticket.mapper.PaymentAuditMapper.class),session.getMapper(io.github.chenforegone.encoreticket.mapper.RefundMapper.class),
                new PaymentIdempotencyMapper(jdbc),new PaymentSettlementRepository(session.getMapper(io.github.chenforegone.encoreticket.mapper.PaymentSettlementMapper.class)),(no,attempt,amount,currency)->{},time::get,JsonMapper.builder().build());
        var proxy=new org.springframework.aop.framework.ProxyFactory(paymentTarget);
        proxy.setProxyTargetClass(true);
        proxy.addAdvice(new org.springframework.transaction.interceptor.TransactionInterceptor(manager,new org.springframework.transaction.annotation.AnnotationTransactionAttributeSource()));
        return (PaymentServiceImpl)proxy.getProxy();
    }

    @Test void cancelledOrderLatePaymentCompletesCompensationWithoutTakingReplacementSeat() throws Exception {
        var created=service.create(20L,"late-original",request("101"));
        var payments=payments();
        var payment=payments.createOrContinue(Long.valueOf(created.orderId()),20L,"pay");
        payments.handleChannelEvent(event(payment,"cancelled-explicit-failure",false,new BigDecimal("39.90")));
        service.cancel(20L,created.orderId());
        var replacement=service.create(20L,"replacement-late",request("101"));
        String attempt=jdbc.queryForObject("SELECT attempt_id FROM payment WHERE id=?",String.class,Long.valueOf(payment.paymentId()));
        payments.handleChannelEvent(new PaymentChannelEvent("SIMULATED","late-event",payment.paymentNo(),attempt,"SUCCESS",new java.math.BigDecimal("39.90"),"CNY",true));
        assertThat(service.detail(20L,created.orderId()).status()).isEqualTo("REFUNDING");
        assertSeats(replacement.orderId(),1,1);
        var refunds=refunds();
        assertThat(refunds.runPending(10)).isEqualTo(1);
        assertSeats(replacement.orderId(),1,1);
        assertThat(service.detail(20L,created.orderId()).status()).isEqualTo("REFUNDED");
        assertThat(service.detail(20L,replacement.orderId()).status()).isEqualTo("PENDING_PAYMENT");
        service.cancel(20L,replacement.orderId());
        var after=service.create(20L,"after-refund",request("101"));
        assertThat(after.orderId()).isNotEqualTo(replacement.orderId());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ticket",Long.class)).isZero();
    }

    private RefundServiceImpl refunds(RefundGateway gateway) {
        return new RefundServiceImpl(new RefundProcessingMapper(jdbc),new DataSourceTransactionManager(datasource),
                time::get,JsonMapper.builder().build(),gateway);
    }

    private RefundServiceImpl refunds() { return refunds(new SimulatedRefundGateway(jdbc,time::get)); }

    private TicketVerificationServiceImpl verification(TicketVerificationMapper mapper) {
        var converter=new TicketVerificationConverter();
        var transaction=new TicketVerificationTransaction(mapper,converter,time::get,JsonMapper.builder().build(),
                new DataSourceTransactionManager(datasource));
        return new TicketVerificationServiceImpl(transaction,mapper,converter);
    }

    private TicketVerificationRequest verificationRequest(String orderId) {
        String code=jdbc.queryForObject("SELECT t.ticket_code FROM ticket t JOIN order_item i ON i.id=t.order_item_id WHERE i.order_id=? ORDER BY t.id LIMIT 1",String.class,Long.valueOf(orderId));
        return JsonMapper.builder().build().readValue(JsonMapper.builder().build().writeValueAsString(
                java.util.Map.of("ticketCode",code,"screeningId","40")),TicketVerificationRequest.class);
    }

    private PaymentChannelEvent event(PaymentVO payment,String eventId,boolean success,BigDecimal amount) {
        String attempt=jdbc.queryForObject("SELECT attempt_id FROM payment WHERE id=?",String.class,Long.valueOf(payment.paymentId()));
        return new PaymentChannelEvent("SIMULATED",eventId,payment.paymentNo(),attempt,
                success?"SUCCESS":"FAILED",amount,"CNY",success);
    }

    private PaymentVO pay(PaymentServiceImpl payments,String orderId,String key) {
        var pending=payments.createOrContinue(Long.valueOf(orderId),20L,key);
        assertThat(payments.handleChannelEvent(event(pending,key+"-success",true,new BigDecimal(pending.amount()))).status()).isEqualTo("SUCCESS");
        assertThat(service.detail(20L,orderId).status()).isEqualTo("PAID");
        return pending;
    }

    private List<Integer> ticketStates(String orderId) {
        return jdbc.queryForList("SELECT t.status FROM ticket t JOIN order_item i ON i.id=t.order_item_id WHERE i.order_id=? ORDER BY t.id",Integer.class,Long.valueOf(orderId));
    }

    private void assertSeats(String orderId,int status,int count) {
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM screening_seat WHERE order_id=? AND status=?",Integer.class,Long.valueOf(orderId),status)).isEqualTo(count);
    }

    private void openAdmission() {
        jdbc.update("INSERT INTO sys_user(id,username,password,status,create_time,update_time) VALUES(99,'order-staff','unused',0,?,?)",utc(),utc());
        // 入场早于退票截止，确保互斥测试不是由时间资格直接拒绝。
        jdbc.update("UPDATE screening SET admission_open_time='2026-10-20 12:00:00' WHERE id=40");
    }

    @Test void purchasePaymentTicketsAndVerificationKeepOneSuccessPerItem() throws Exception {
        var created=service.create(20L,"journey-verify",request("101","102"));
        assertThat(ticketStates(created.orderId())).isEmpty();
        assertSeats(created.orderId(),1,2);
        var payments=payments();
        pay(payments,created.orderId(),"journey-pay");
        assertThat(ticketStates(created.orderId())).containsExactly(0,0);
        assertSeats(created.orderId(),2,2);
        openAdmission();
        var verification=verification(new TicketVerificationMapper(jdbc));
        var request=verificationRequest(created.orderId());
        var used=verification.verify(99L,"journey-scan",request);
        assertThat(used.status()).isEqualTo("USED");
        assertThat(verification.verify(99L,"journey-scan",request)).isEqualTo(used);
        assertThatThrownBy(()->verification.verify(99L,"journey-scan-new",request))
                .isInstanceOf(TicketVerificationException.class)
                .satisfies(e->assertThat(((ApplicationException)e).getErrorCode().name()).isEqualTo("TICKET_ALREADY_USED"));
        assertThat(ticketStates(created.orderId())).containsExactlyInAnyOrder(1,0);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ticket_verification WHERE operator_id=99",Integer.class)).isEqualTo(1);
        assertThat(service.detail(20L,created.orderId()).status()).isEqualTo("PAID");
        assertSeats(created.orderId(),2,2);
        assertThatThrownBy(()->refunds().apply(Long.valueOf(created.orderId()),20L,"used-refund"))
                .isInstanceOf(RefundException.class)
                .satisfies(e->assertThat(((ApplicationException)e).getErrorCode().name()).isEqualTo("TICKET_ALREADY_USED"));
    }

    @Test void refundTaskVoidsAllTicketsAndAllowsTheSameSeatsToBeSoldAgain() throws Exception {
        var created=service.create(20L,"journey-refund",request("101","102"));
        var payments=payments();
        var payment=pay(payments,created.orderId(),"refund-pay");
        var refunds=refunds();
        var accepted=refunds.apply(Long.valueOf(created.orderId()),20L,"refund-apply");
        assertThat(accepted.amount()).isEqualTo("79.80");
        assertThat(accepted.status()).isEqualTo("PENDING");
        assertThat(service.detail(20L,created.orderId()).status()).isEqualTo("REFUNDING");
        assertThat(ticketStates(created.orderId())).containsExactly(0,0);
        assertSeats(created.orderId(),2,2);
        openAdmission();
        assertThatThrownBy(()->verification(new TicketVerificationMapper(jdbc)).verify(99L,"refunding-scan",verificationRequest(created.orderId())))
                .isInstanceOf(TicketVerificationException.class)
                .satisfies(e->assertThat(((ApplicationException)e).getErrorCode().name()).isEqualTo("ORDER_NOT_ADMISSIBLE"));
        assertThat(refunds.runPending(10)).isEqualTo(1);
        assertThat(service.detail(20L,created.orderId()).status()).isEqualTo("REFUNDED");
        assertThat(ticketStates(created.orderId())).containsExactly(2,2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM screening_seat WHERE id IN (101,102) AND status=0 AND order_id IS NULL",Integer.class)).isEqualTo(2);
        var replacement=service.create(20L,"refund-resale",request("101","102"));
        pay(payments,replacement.orderId(),"resale-pay");
        assertSeats(replacement.orderId(),2,2);
        assertThat(ticketStates(replacement.orderId())).containsExactly(0,0);
        assertThat(refunds.apply(Long.valueOf(created.orderId()),20L,"refund-apply")).isEqualTo(accepted);
        assertThat(refunds.runPending(10)).isZero();
        payments.handleChannelEvent(event(payment,"refund-pay-success",true,new BigDecimal("79.800")));
        payments.handleChannelEvent(event(payment,"refunded-fresh-success",true,new BigDecimal("79.80")));
        assertThat(service.detail(20L,created.orderId()).status()).isEqualTo("REFUNDED");
        assertThat(ticketStates(created.orderId())).containsExactly(2,2);
        assertSeats(replacement.orderId(),2,2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM refund_simulated_result",Integer.class)).isEqualTo(1);
    }

    @Test void concurrentAndNormalizedDuplicateNotificationsNeverReissueTickets() throws Exception {
        var created=service.create(20L,"notify-order",request("101","102"));
        var payments=payments();
        var payment=payments.createOrContinue(Long.valueOf(created.orderId()),20L,"notify-pay");
        var first=event(payment,"notify-duplicate",true,new BigDecimal("79.80"));
        var normalized=event(payment,"notify-duplicate",true,new BigDecimal("79.800"));
        try(var pool=new TestTasks()) {
            var start=new CountDownLatch(1);
            var a=pool.submit(()->{awaitRelease(start);return payments.handleChannelEvent(first);});
            var b=pool.submit(()->{awaitRelease(start);return payments.handleChannelEvent(normalized);});
            start.countDown();
            assertThat(a.get(10,TimeUnit.SECONDS)).isEqualTo(b.get(10,TimeUnit.SECONDS));
        }
        payments.handleChannelEvent(event(payment,"notify-new-success",true,new BigDecimal("79.80")));
        payments.handleChannelEvent(event(payment,"notify-later-failure",false,new BigDecimal("79.80")));
        assertThatThrownBy(()->payments.handleChannelEvent(event(payment,"notify-duplicate",false,new BigDecimal("79.80"))))
                .isInstanceOf(io.github.chenforegone.encoreticket.common.exception.ConflictException.class)
                .satisfies(e->assertThat(((ApplicationException)e).getErrorCode().name()).isEqualTo("PAYMENT_IDEMPOTENCY_CONFLICT"));
        assertThat(payments.listByOrder(Long.valueOf(created.orderId()),20L).items()).extracting(PaymentVO::status).containsExactly("SUCCESS");
        assertThat(ticketStates(created.orderId())).containsExactly(0,0);
        assertSeats(created.orderId(),2,2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM payment_notification",Integer.class)).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM payment_audit WHERE event_id='notify-duplicate'",Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM refund",Integer.class)).isZero();
    }

    @Test void expiredLateSuccessAndCompensationCannotReleaseAnAlreadySoldReplacement() throws Exception {
        var original=service.create(20L,"expired-original",request("101"));
        var payments=payments();
        var payment=payments.createOrContinue(Long.valueOf(original.orderId()),20L,"expired-pay");
        time.set(NOW.plusSeconds(900));
        var replacement=service.create(20L,"expired-replacement",request("101"));
        assertThat(service.detail(20L,original.orderId()).status()).isEqualTo("EXPIRED");
        pay(payments,replacement.orderId(),"replacement-pay");
        var late=event(payment,"expired-late",true,new BigDecimal("39.90"));
        payments.handleChannelEvent(late);
        payments.handleChannelEvent(late);
        assertThat(service.detail(20L,original.orderId()).status()).isEqualTo("REFUNDING");
        assertSeats(replacement.orderId(),2,1);
        var refunds=refunds();
        assertThat(refunds.byOrder(Long.valueOf(original.orderId()),20L).items()).singleElement().satisfies(r->assertThat(r.source()).isEqualTo("LATE_PAYMENT"));
        assertThat(refunds.runPending(10)).isEqualTo(1);
        payments.handleChannelEvent(event(payment,"expired-late-new",true,new BigDecimal("39.900")));
        assertThat(refunds.runPending(10)).isZero();
        assertThat(service.detail(20L,original.orderId()).status()).isEqualTo("REFUNDED");
        assertThat(service.detail(20L,replacement.orderId()).status()).isEqualTo("PAID");
        assertThat(ticketStates(original.orderId())).isEmpty();
        assertThat(ticketStates(replacement.orderId())).containsExactly(0);
        assertSeats(replacement.orderId(),2,1);
        openAdmission();
        assertThat(verification(new TicketVerificationMapper(jdbc)).verify(99L,"replacement-scan",verificationRequest(replacement.orderId())).status()).isEqualTo("USED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM refund_simulated_result",Integer.class)).isEqualTo(1);
    }

    @Test void refundChannelSuccessWithLocalRollbackRecoversByQueryWithoutAnotherSubmit() throws Exception {
        var created=service.create(20L,"recovery-order",request("101","102"));
        pay(payments(),created.orderId(),"recovery-pay");
        var refunds=refunds();
        var accepted=refunds.apply(Long.valueOf(created.orderId()),20L,"recovery-refund");
        jdbc.execute("ALTER TABLE refund_audit ADD CONSTRAINT ck_order_refund_fault CHECK (operation<>'CHANNEL_SUCCESS')");
        try {
            assertThatThrownBy(()->refunds.processOne(Long.parseLong(accepted.refundId())))
                    .isInstanceOf(org.springframework.dao.DataAccessException.class);
            assertThat(service.detail(20L,created.orderId()).status()).isEqualTo("REFUNDING");
            assertThat(ticketStates(created.orderId())).containsExactly(0,0);
            assertSeats(created.orderId(),2,2);
            assertThat(jdbc.queryForObject("SELECT status FROM refund WHERE id=?",Integer.class,Long.valueOf(accepted.refundId()))).isZero();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM refund_simulated_result",Integer.class)).isEqualTo(1);
        } finally { jdbc.execute("ALTER TABLE refund_audit DROP CHECK ck_order_refund_fault"); }
        time.set(NOW.plusSeconds(61));
        var recoveryQueries=new java.util.concurrent.atomic.AtomicInteger();
        var persistedGateway=new SimulatedRefundGateway(jdbc,time::get);
        var restarted=refunds(new RefundGateway() {
            @Override public Result submit(String refundNo,int attempt,String paymentNo,BigDecimal amount) {
                throw new AssertionError("恢复阶段禁止再次调用 submit");
            }
            @Override public Result query(String refundNo,int attempt,String paymentNo,BigDecimal amount) {
                recoveryQueries.incrementAndGet();
                assertThat(refundNo).isEqualTo(accepted.refundNo());
                assertThat(attempt).isEqualTo(1);
                var result=persistedGateway.query(refundNo,attempt,paymentNo,amount);
                assertThat(result).isEqualTo(Result.SUCCESS);
                return result;
            }
        });
        assertThat(restarted.runPending(10)).isEqualTo(1);
        assertThat(recoveryQueries.get()).isEqualTo(1);
        assertThat(restarted.runPending(10)).isZero();
        assertThat(recoveryQueries.get()).isEqualTo(1);
        assertThat(service.detail(20L,created.orderId()).status()).isEqualTo("REFUNDED");
        assertThat(ticketStates(created.orderId())).containsExactly(2,2);
        assertThat(restarted.adminDetail(Long.valueOf(accepted.refundId())).attempts()).hasSize(1);
        assertThat(restarted.adminDetail(Long.valueOf(accepted.refundId())).audits()).filteredOn(a->a.operation().equals("CHANNEL_SUCCESS")).hasSize(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM refund_simulated_result",Integer.class)).isEqualTo(1);
        var replacement=service.create(20L,"recovery-resale",request("101","102"));
        pay(payments(),replacement.orderId(),"recovery-resale-pay");
        assertSeats(replacement.orderId(),2,2);
    }

    @Test void purchasedTicketVerificationWinningSharedLockRejectsWholeOrderRefund() throws Exception { competeAfterPurchase(true); }
    @Test void purchasedOrderRefundWinningSharedLockRejectsVerification() throws Exception { competeAfterPurchase(false); }

    private void competeAfterPurchase(boolean verificationWins) throws Exception {
        requireLockWaitObservation();
        var created=service.create(20L,"mutex-order",request("101","102"));
        pay(payments(),created.orderId(),"mutex-pay");
        openAdmission();
        var held=new CountDownLatch(1);
        var release=new CountDownLatch(1);
        var attemptingLock=new CountDownLatch(1);
        var refundConnection=new AtomicReference<Long>();
        var verificationConnection=new AtomicReference<Long>();
        var refundMapper=new RefundProcessingMapper(jdbc) {
            @Override public boolean lockScreening(long id) {
                refundConnection.set(jdbc.queryForObject("SELECT CONNECTION_ID()",Long.class));
                if(verificationWins) attemptingLock.countDown();
                boolean locked=super.lockScreening(id);
                if(!verificationWins) { held.countDown(); awaitRelease(release); }
                return locked;
            }
        };
        var verificationMapper=new TicketVerificationMapper(jdbc) {
            @Override public Screening lockScreening(long id) {
                verificationConnection.set(jdbc.queryForObject("SELECT CONNECTION_ID()",Long.class));
                if(!verificationWins) attemptingLock.countDown();
                var locked=super.lockScreening(id);
                if(verificationWins) { held.countDown(); awaitRelease(release); }
                return locked;
            }
        };
        var refunds=new RefundServiceImpl(refundMapper,new DataSourceTransactionManager(datasource),time::get,
                JsonMapper.builder().build(),new SimulatedRefundGateway(jdbc,time::get));
        var verification=verification(verificationMapper);
        var request=verificationRequest(created.orderId());
        Callable<Object> refund=()->refunds.apply(Long.valueOf(created.orderId()),20L,"mutex-refund");
        Callable<Object> verify=()->verification.verify(99L,"mutex-scan",request);
        try(var pool=new TestTasks()) {
            var winner=pool.submit(verificationWins?verify:refund);
            try {
                assertThat(held.await(10,TimeUnit.SECONDS)).isTrue();
                var loser=pool.submit(verificationWins?refund:verify);
                assertThat(attemptingLock.await(10,TimeUnit.SECONDS)).isTrue();
                awaitScreeningLockWait(verificationWins?refundConnection.get():verificationConnection.get(),
                        verificationWins?verificationConnection.get():refundConnection.get(),loser);
                release.countDown();
                assertThat(winner.get(10,TimeUnit.SECONDS)).isNotNull();
                assertThatThrownBy(()->loser.get(10,TimeUnit.SECONDS))
                        .hasCauseInstanceOf(verificationWins?RefundException.class:TicketVerificationException.class)
                        .satisfies(e->assertThat(((ApplicationException)e.getCause()).getErrorCode().name())
                                .isEqualTo(verificationWins?"TICKET_ALREADY_USED":"ORDER_NOT_ADMISSIBLE"));
            } finally { release.countDown(); }
        } finally { release.countDown(); }
        assertThat(service.detail(20L,created.orderId()).status()).isEqualTo(verificationWins?"PAID":"REFUNDING");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ticket_verification",Integer.class)).isEqualTo(verificationWins?1:0);
        assertThat(refunds.byOrder(Long.valueOf(created.orderId()),20L).items()).hasSize(verificationWins?0:1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM api_idempotency WHERE operation=?",Integer.class,
                verificationWins?RefundServiceImpl.APPLY:TicketVerificationTransaction.OPERATION)).isZero();
        assertSeats(created.orderId(),2,2);
        if(verificationWins) assertThat(ticketStates(created.orderId())).containsExactlyInAnyOrder(1,0);
        else {
            assertThat(refunds().runPending(10)).isEqualTo(1);
            assertThat(ticketStates(created.orderId())).containsExactly(2,2);
            assertThat(service.detail(20L,created.orderId()).status()).isEqualTo("REFUNDED");
        }
    }

    // 只观察本次隔离库的 screening 主键记录锁，不修改全局监控配置或权限。
    private static final String SCREENING_LOCK_WAITS="""
            SELECT COUNT(*) FROM performance_schema.data_lock_waits w
            JOIN performance_schema.data_locks l
              ON l.ENGINE=w.ENGINE AND l.ENGINE_LOCK_ID=w.REQUESTING_ENGINE_LOCK_ID
            JOIN performance_schema.threads requester ON requester.THREAD_ID=w.REQUESTING_THREAD_ID
            JOIN performance_schema.threads blocker ON blocker.THREAD_ID=w.BLOCKING_THREAD_ID
            WHERE l.OBJECT_SCHEMA=DATABASE() AND l.OBJECT_NAME='screening'
              AND l.INDEX_NAME='PRIMARY' AND l.LOCK_TYPE='RECORD'
              AND l.LOCK_STATUS='WAITING' AND l.LOCK_DATA='40'
              AND requester.PROCESSLIST_ID=? AND blocker.PROCESSLIST_ID=?
            """;

    private static void requireLockWaitObservation() {
        org.junit.jupiter.api.Assumptions.assumeTrue(
                jdbc.queryForObject("SELECT @@performance_schema",Integer.class)==1,
                "performance_schema 未启用，跳过真实锁等待证明，不能视为通过");
        try {
            jdbc.queryForObject(SCREENING_LOCK_WAITS,Integer.class,-1L,-1L);
        } catch(org.springframework.dao.DataAccessException e) {
            Throwable cause=e.getMostSpecificCause();
            if(cause instanceof java.sql.SQLException sql
                    && List.of(1044,1142,1146,1227).contains(sql.getErrorCode())) {
                org.junit.jupiter.api.Assumptions.abort("锁等待观测表或权限不可用，跳过而非宣称互斥通过");
            }
            throw e;
        }
    }

    private static void awaitScreeningLockWait(Long requester,Long blocker,Future<?> loser) {
        assertThat(requester).isNotNull().isNotEqualTo(blocker);
        assertThat(blocker).isNotNull();
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);
        do {
            if(jdbc.queryForObject(SCREENING_LOCK_WAITS,Integer.class,requester,blocker)>0) return;
            assertThat(loser.isDone()).as("败方在观测到真实锁等待前不应结束").isFalse();
            java.util.concurrent.locks.LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(20));
            if(Thread.currentThread().isInterrupted()) throw new IllegalStateException("锁等待观测被中断");
        } while(System.nanoTime()<deadline);
        fail("未在有界期限内观测到败方被胜方的 screening 主键记录锁阻塞");
    }

    private static void awaitRelease(CountDownLatch release) {
        try { assertThat(release.await(10,TimeUnit.SECONDS)).isTrue(); }
        catch(InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
    }

    @Test void invalidSeatCountsAndChangedIdempotentContentAreRejected() {
        for(var seats:List.of(new String[]{},new String[]{"101","102","103","104","105","106","107"}))
            assertThatThrownBy(()->service.create(20L,"invalid",request(seats))).isInstanceOf(io.github.chenforegone.encoreticket.common.exception.InvalidRequestException.class);
        service.create(20L,"content",request("101"));
        assertThatThrownBy(()->service.create(20L,"content",request("102")))
                .isInstanceOf(io.github.chenforegone.encoreticket.common.exception.ConflictException.class)
                .extracting(e->((io.github.chenforegone.encoreticket.common.exception.ApplicationException)e).getErrorCode().name()).isEqualTo("IDEMPOTENCY_CONFLICT");
    }

    @Test void sixSeatsCanBePurchasedAndCancellationIsStateIdempotent() {
        var created=service.create(20L,"six",request("106","105","104","103","102","101"));
        assertThat(created.totalAmount()).isEqualTo("239.40");
        assertThat(created.items()).hasSize(6);
        assertThat(service.cancel(20L,created.orderId())).isEqualTo(service.cancel(20L,created.orderId()));
    }

    @Test void concurrentSameKeyReplaysOneSnapshot() throws Exception {
        try(var pool=new TestTasks()) {
            var start=new java.util.concurrent.CountDownLatch(1);
            var a=pool.submit(()->{awaitRelease(start);return service.create(20L,"same",request("101","102"));});
            var b=pool.submit(()->{awaitRelease(start);return service.create(20L,"same",request("102","101"));});
            start.countDown();
            assertThat(a.get(10,java.util.concurrent.TimeUnit.SECONDS)).isEqualTo(b.get(10,java.util.concurrent.TimeUnit.SECONDS));
        }
    }

    @Test void concurrentDifferentKeysAllowOnlyOneLiveOrder() throws Exception {
        try(var pool=new TestTasks()) {
            var start=new java.util.concurrent.CountDownLatch(1);
            var a=pool.submit(()->{awaitRelease(start);try{return service.create(20L,"parallel-a",request("101")).orderId();}catch(io.github.chenforegone.encoreticket.common.exception.ConflictException e){return "conflict";}});
            var b=pool.submit(()->{awaitRelease(start);try{return service.create(20L,"parallel-b",request("102")).orderId();}catch(io.github.chenforegone.encoreticket.common.exception.ConflictException e){return "conflict";}});
            start.countDown();
            assertThat(List.of(a.get(10,java.util.concurrent.TimeUnit.SECONDS),b.get(10,java.util.concurrent.TimeUnit.SECONDS))).filteredOn("conflict"::equals).hasSize(1);
        }
        assertThat(service.list(20L,null).total()).isEqualTo(1);
    }

    @Test void snapshotExpiresAtExactly24HoursAndCanCreateANewOrder() {
        var first=service.create(20L,"24h",request("101"));
        service.cancel(20L,first.orderId());
        time.set(NOW.plusSeconds(86399));
        assertThat(service.create(20L,"24h",request("101"))).isEqualTo(first);
        time.set(NOW.plusSeconds(86400));
        assertThat(service.create(20L,"24h",request("101")).orderId()).isNotEqualTo(first.orderId());
    }

    @Test void expiredOrderIsReleasedBeforeANewPurchase() {
        var old=service.create(20L,"old",request("101"));
        time.set(NOW.plusSeconds(900));
        assertThat(service.create(20L,"new",request("101")).orderId()).isNotEqualTo(old.orderId());
        assertThat(service.detail(20L,old.orderId()).status()).isEqualTo("EXPIRED");
    }

    @Test void expiredOrderReleaseRemainsCommittedWhenReplacementPurchaseFails() {
        var old=service.create(20L,"old-before-failure",request("101"));
        jdbc.update("UPDATE seat SET status=1 WHERE id=2");
        time.set(NOW.plusSeconds(900));
        assertThatThrownBy(()->service.create(20L,"failed-replacement",request("102")))
                .isInstanceOf(io.github.chenforegone.encoreticket.common.exception.ConflictException.class);
        assertThat(service.detail(20L,old.orderId()).updatedAt()).isEqualTo(NOW.plusSeconds(900));
        jdbc.update("INSERT INTO sys_user(id,username,password,status,create_time,update_time) VALUES(21,'replacement-buyer','unused',0,?,?)",utc(),utc());
        assertThat(service.create(21L,"released-seat",request("101")).status()).isEqualTo("PENDING_PAYMENT");
    }

    @Test void httpCreatesListsReadsAndCancelsOrders() throws Exception {
        var mvc=org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(
                new io.github.chenforegone.encoreticket.controller.user.UserOrderWorkflowController(service))
                .setControllerAdvice(new io.github.chenforegone.encoreticket.common.exception.GlobalExceptionHandler())
                .addFilters(new io.github.chenforegone.encoreticket.security.filter.RequestIdFilter()).build();
        var principal=new org.springframework.security.authentication.UsernamePasswordAuthenticationToken("20",null,List.of());
        var response=mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/orders")
                .principal(principal).header("Idempotency-Key","http").contentType("application/json")
                .content("{\"screeningId\":\"40\",\"screeningSeatIds\":[\"101\"]}"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isCreated()).andReturn();
        assertThat(response.getResponse().getHeader("X-Request-Id")).isNotBlank();
        String id=JsonMapper.builder().build().readTree(response.getResponse().getContentAsString()).get("data").get("orderId").asText();
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/orders/"+id)
                .principal(new org.springframework.security.authentication.UsernamePasswordAuthenticationToken("21",null,List.of())))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isNotFound())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/orders").principal(principal)
                .header("Idempotency-Key","numeric").contentType("application/json").content("{\"screeningId\":40,\"screeningSeatIds\":[\"101\"]}"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isBadRequest());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/orders").principal(principal))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.data.total").value(1));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/orders/"+id).principal(principal))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.data.totalAmount").value("39.90"));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/orders/"+id+"/cancel").principal(principal))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.data.status").value("CANCELLED"));
    }

    @Test void listFiltersByDerivedExpirationAtOneRequestTime() {
        service.create(20L,"listing",request("101"));
        time.set(NOW.plusSeconds(900));
        var query=new io.github.chenforegone.encoreticket.model.dto.OrderQueryRequest(1,20,"EXPIRED",null,null);
        assertThat(service.list(20L,query).total()).isEqualTo(1);
        assertThat(service.list(20L,query).items()).extracting(o->o.status()).containsExactly("EXPIRED");
        assertThat(service.list(20L,new io.github.chenforegone.encoreticket.model.dto.OrderQueryRequest(1,20,"PENDING_PAYMENT",null,null)).total()).isZero();
    }

    @Test void pendingPaymentBlocksOnlyUnexpiredCancellation() {
        var created=service.create(20L,"pending",request("101"));
        jdbc.update("INSERT INTO payment(id,payment_no,order_id,amount,status,attempt_id,create_time,update_time) VALUES(300,'order-pay',?,39.90,0,'attempt',?,?)",Long.valueOf(created.orderId()),utc(),utc());
        try {
            assertThatThrownBy(()->service.cancel(20L,created.orderId()))
                    .isInstanceOf(io.github.chenforegone.encoreticket.common.exception.ApplicationException.class)
                    .extracting(e->((io.github.chenforegone.encoreticket.common.exception.ApplicationException)e).getErrorCode().name()).isEqualTo("PAYMENT_RESULT_UNKNOWN");
            time.set(NOW.plusSeconds(900));
            assertThatThrownBy(()->service.cancel(20L,created.orderId())).isInstanceOf(io.github.chenforegone.encoreticket.common.exception.ApplicationException.class);
            assertThat(service.detail(20L,created.orderId()).status()).isEqualTo("EXPIRED");
        } finally { jdbc.update("DELETE FROM payment WHERE id=300"); }
    }

    @Test void getDerivesExpiredWithoutReleasingSeats() {
        var created=service.create(20L,"readonly",request("101"));
        time.set(NOW.plusSeconds(900));
        var expired=service.detail(20L,created.orderId());
        assertThat(expired.status()).isEqualTo("EXPIRED");
        assertThat(expired.updatedAt()).isEqualTo(NOW);
        assertThatThrownBy(()->service.detail(21L,created.orderId()))
                .isInstanceOf(io.github.chenforegone.encoreticket.common.exception.ResourceNotFoundException.class);
    }

    @Test void cancellationAtDeadlineCommitsExpirationDespiteConflict() {
        var created=service.create(20L,"expired",request("101"));
        time.set(NOW.plusSeconds(900));
        assertThatThrownBy(()->service.cancel(20L,created.orderId()))
                .isInstanceOf(io.github.chenforegone.encoreticket.common.exception.ApplicationException.class);
        assertThat(service.detail(20L,created.orderId()).status()).isEqualTo("EXPIRED");
        assertThat(service.create(20L,"new",request("101")).orderId()).isNotEqualTo(created.orderId());
    }

    @Test void cancelReleasesSeatsAndRetainsCreationSnapshot() {
        var created=service.create(20L,"original",request("101"));
        assertThat(service.cancel(20L,created.orderId()).status()).isEqualTo("CANCELLED");
        assertThat(service.detail(20L,created.orderId()).status()).isEqualTo("CANCELLED");
        assertThat(service.create(20L,"original",request("101"))).isEqualTo(created);
        assertThat(service.create(20L,"replacement",request("101")).orderId()).isNotEqualTo(created.orderId());
    }

    @Test void unavailablePhysicalSeatRejectsTheWholeOrder() {
        jdbc.update("UPDATE seat SET status=1 WHERE id=2");
        assertThatThrownBy(()->service.create(20L,"disabled",request("101","102")))
                .isInstanceOf(io.github.chenforegone.encoreticket.common.exception.ApplicationException.class);
        assertThat(service.create(20L,"remaining",request("101")).seatCount()).isEqualTo(1);
    }

    @Test void secondLiveOrderForSameScreeningIsRejected() {
        service.create(20L,"one",request("101"));
        assertThatThrownBy(()->service.create(20L,"two",request("102")))
                .isInstanceOf(io.github.chenforegone.encoreticket.common.exception.ApplicationException.class);
    }

    @Test void sameKeyReplaysTheOriginalCreation() {
        var first=service.create(20L,"stable",request("101","102"));
        assertThat(service.create(20L,"stable",request("102","101"))).isEqualTo(first);
    }

    @Test void duplicateSeatsAreRejectedWithoutCreatingAnOrder() {
        assertThatThrownBy(()->service.create(20L,"duplicates",request("101","101")))
                .isInstanceOf(io.github.chenforegone.encoreticket.common.exception.InvalidRequestException.class);
        assertThat(service.create(20L,"valid",request("101")).totalAmount()).isEqualTo("39.90");
    }

    @Test void userCanCreateAnOrderWithServerCalculatedPrice() {
        var order=service.create(20L,"first",request("102","101"));
        assertThat(order.status()).isEqualTo("PENDING_PAYMENT");
        assertThat(order.totalAmount()).isEqualTo("79.80");
        assertThat(order.expiresAt()).isEqualTo(NOW.plusSeconds(900));
        assertThat(order.items()).extracting(i->i.screeningSeatId()).containsExactly("101","102");
    }
}
