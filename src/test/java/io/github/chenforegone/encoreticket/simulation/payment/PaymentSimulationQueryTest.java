package io.github.chenforegone.encoreticket.simulation.payment;



import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 测试专用只读尝试查询的公开服务契约。
 * @author c1342
 */
class PaymentSimulationQueryTest {
    private JdbcClient database() {
        var jdbc = JdbcClient.create(new DriverManagerDataSource(
                "jdbc:h2:mem:" + java.util.UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", ""));
        jdbc.sql("CREATE TABLE payment(id BIGINT PRIMARY KEY, payment_no VARCHAR(64), attempt_id VARCHAR(64), amount DECIMAL(10,2), currency VARCHAR(3))").update();
        jdbc.sql("CREATE TABLE payment_audit(id BIGINT PRIMARY KEY, payment_id BIGINT, operation VARCHAR(64), remark VARCHAR(255), create_time TIMESTAMP)").update();
        jdbc.sql("INSERT INTO payment VALUES(1, 'P1', 'legacy-1', 88.00, 'CNY')").update();
        return jdbc;
    }

    @Test
    void springProxyKeepsCurrentAttemptAndHistoryInOneReadOnlySnapshot() {
        try (var context = new org.springframework.context.annotation.AnnotationConfigApplicationContext()) {
            context.getEnvironment().setActiveProfiles("test");
            context.getEnvironment().getPropertySources().addFirst(new org.springframework.core.env.MapPropertySource(
                    "simulation", java.util.Map.of("encore.payment.simulation.enabled", "true")));
            context.register(SnapshotFixture.class, PaymentSimulationService.class);
            context.refresh();
            var source = context.getBean(SnapshotDataSource.class);
            var service = context.getBean(PaymentSimulationService.class);
            var page = service.attempts("P1", 20, null);
            assertThat(page.items()).containsExactly(new PaymentSimulationService.Attempt("P1", "legacy-1", null, true));
            assertThat(org.springframework.aop.support.AopUtils.isAopProxy(service)).isTrue();
            assertThat(source.observedReadOnly).isTrue();
            assertThat(source.observedIsolation).isEqualTo(java.sql.Connection.TRANSACTION_REPEATABLE_READ);
            // 两次查询之间已经提交了新当前尝试，本次仍返回旧快照，后续请求看到新值。
            assertThat(service.attempts("P1", 20, null).items()).containsExactly(
                    new PaymentSimulationService.Attempt("P1", "new-attempt", null, true));
        }
    }

    /**
     * 使用实际 Spring 事务代理及独立连接的查询夹具。
     * @author c1342
     */
    @org.springframework.context.annotation.Configuration
    @org.springframework.transaction.annotation.EnableTransactionManagement(proxyTargetClass = true)
    static class SnapshotFixture {
        @org.springframework.context.annotation.Bean SnapshotDataSource dataSource() { return new SnapshotDataSource(); }
        @org.springframework.context.annotation.Bean JdbcClient jdbcClient(SnapshotDataSource source) {
            var jdbc = JdbcClient.create(source.delegate);
            jdbc.sql("CREATE TABLE payment(id BIGINT PRIMARY KEY, payment_no VARCHAR(64), attempt_id VARCHAR(64), amount DECIMAL(10,2), currency VARCHAR(3))").update();
            jdbc.sql("CREATE TABLE payment_audit(id BIGINT PRIMARY KEY, payment_id BIGINT, operation VARCHAR(64), remark VARCHAR(255), create_time TIMESTAMP)").update();
            jdbc.sql("INSERT INTO payment VALUES(1,'P1','legacy-1',88.00,'CNY')").update();
            return JdbcClient.create(source);
        }
        @org.springframework.context.annotation.Bean org.springframework.jdbc.support.JdbcTransactionManager transactionManager(SnapshotDataSource source) {
            return new org.springframework.jdbc.support.JdbcTransactionManager(source);
        }
    }

    /**
     * 在第一条 SELECT 完成后提交并发变更，观察公开查询是否保持快照。
     * @author c1342
     */
    static class SnapshotDataSource extends org.springframework.jdbc.datasource.AbstractDataSource {
        final DriverManagerDataSource delegate = new DriverManagerDataSource(
                "jdbc:h2:mem:" + java.util.UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        boolean changed;
        boolean observedReadOnly;
        int observedIsolation;
        @Override public java.sql.Connection getConnection() throws java.sql.SQLException {
            var connection = delegate.getConnection();
            return (java.sql.Connection) java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[]{java.sql.Connection.class}, (proxy, method, args) -> {
                        Object value = invoke(connection, method, args);
                        if (method.getName().equals("prepareStatement") && args[0].toString().startsWith("SELECT id, attempt_id")) {
                            observedReadOnly = org.springframework.transaction.support.TransactionSynchronizationManager.isCurrentTransactionReadOnly();
                            observedIsolation = connection.getTransactionIsolation();
                            var statement = (java.sql.PreparedStatement) value;
                            return java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),
                                    new Class<?>[]{java.sql.PreparedStatement.class}, (p, m, a) -> {
                                        Object result = invoke(statement, m, a);
                                        if (m.getName().equals("close") && !changed) {
                                            changed = true;
                                            JdbcClient.create(delegate).sql("UPDATE payment SET attempt_id='new-attempt' WHERE id=1").update();
                                        }
                                        return result;
                                    });
                        }
                        return value;
                    });
        }
        @Override public java.sql.Connection getConnection(String username, String password) throws java.sql.SQLException {
            return getConnection();
        }
        private static Object invoke(Object target, java.lang.reflect.Method method, Object[] args) throws Throwable {
            try { return method.invoke(target, args); }
            catch (java.lang.reflect.InvocationTargetException exception) { throw exception.getCause(); }
        }
    }

    @Test
    void auditHistoryUsesRecordedTimeAndStableBoundedPages() {
        var jdbc = database();
        jdbc.sql("INSERT INTO payment_audit VALUES(10,1,'ATTEMPT_STARTED','old-a',TIMESTAMP '2026-10-19 12:00:00'),(20,1,'ATTEMPT_STARTED','legacy-1',TIMESTAMP '2026-10-19 12:00:00'),(30,1,'CHANNEL_FAILURE','not-an-attempt',TIMESTAMP '2026-10-19 12:01:00')").update();
        var service = new PaymentSimulationService(jdbc);
        var first = service.attempts("P1", 1, null);
        assertThat(first.items()).extracting(PaymentSimulationService.Attempt::attemptId).containsExactly("old-a");
        assertThat(first.items().getFirst().createdAt()).isEqualTo(java.time.Instant.parse("2026-10-19T12:00:00Z"));
        assertThat(first.items().getFirst().current()).isFalse();
        assertThat(first.nextCursor()).isNotNull();
        var second = service.attempts("P1", 1, first.nextCursor());
        assertThat(second.items()).extracting(PaymentSimulationService.Attempt::attemptId).containsExactly("legacy-1");
        assertThat(second.items().getFirst().current()).isTrue();
        assertThat(second.nextCursor()).isNull();
        assertThat(service.attempts("P1", 20, null).items()).hasSize(2);
    }

    @Test
    void legacyAndMoreThanOneHundredAuditedAttemptsRemainReachableWithoutWrites() {
        var jdbc = database();
        for (int i = 1; i <= 105; i++) {
            jdbc.sql("INSERT INTO payment_audit VALUES(:id,1,'ATTEMPT_STARTED',:attempt,TIMESTAMP '2026-10-19 12:00:00')")
                    .param("id", i).param("attempt", "attempt-" + i).update();
        }
        var service = new PaymentSimulationService(jdbc);
        var first = service.attempts("P1", 100, null);
        assertThat(first.items()).hasSize(100);
        assertThat(first.items().getFirst().attemptId()).isEqualTo("legacy-1");
        assertThat(first.items().getFirst().createdAt()).isNull();
        var second = service.attempts("P1", 100, first.nextCursor());
        assertThat(second.items()).extracting(PaymentSimulationService.Attempt::attemptId)
                .containsExactly("attempt-100", "attempt-101", "attempt-102", "attempt-103", "attempt-104", "attempt-105");
        assertThat(second.nextCursor()).isNull();
        assertThat(service.attempts("P1", 100, null)).isEqualTo(first);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.attempts("unknown", 20, null))
                .isInstanceOf(io.github.chenforegone.encoreticket.common.exception.ResourceNotFoundException.class);
        for (int limit : new int[]{0, -1, 101, Integer.MAX_VALUE}) {
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.attempts("P1", limit, null))
                    .isInstanceOf(io.github.chenforegone.encoreticket.common.exception.InvalidRequestException.class);
        }
    }

    @Test
    void overwrittenLegacyWithoutAuditCannotBeReconstructedFromPaymentId() {
        var jdbc = database();
        jdbc.sql("UPDATE payment SET attempt_id='retry-attempt' WHERE id=1").update();
        jdbc.sql("INSERT INTO payment_audit VALUES(10,1,'ATTEMPT_STARTED','retry-attempt',TIMESTAMP '2026-10-19 12:00:00')").update();
        var page = new PaymentSimulationService(jdbc).attempts("P1", 20, null);
        assertThat(page.items()).extracting(PaymentSimulationService.Attempt::attemptId).containsExactly("retry-attempt");
        assertThat(page.items().getFirst().createdAt()).isEqualTo(java.time.Instant.parse("2026-10-19T12:00:00Z"));
        assertThat(page.items().getFirst().current()).isTrue();
        assertThat(page.nextCursor()).isNull();
    }

    @Test
    void currentLegacyAttemptHasNoInventedCreationTime() {
        var service = new PaymentSimulationService(database());
        var page = service.attempts("P1", 20, null);
        assertThat(page.items()).hasSize(1);
        assertThat(page.items().getFirst().paymentNo()).isEqualTo("P1");
        assertThat(page.items().getFirst().attemptId()).isEqualTo("legacy-1");
        assertThat(page.items().getFirst().createdAt()).isNull();
        assertThat(page.items().getFirst().current()).isTrue();
        assertThat(page.nextCursor()).isNull();
    }
}
