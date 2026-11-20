package io.github.chenforegone.encoreticket.simulation.payment;



import io.github.chenforegone.encoreticket.gateway.PaymentChannelEvent;

import io.github.chenforegone.encoreticket.service.*;
import io.github.chenforegone.encoreticket.model.vo.PaymentVO;
import io.github.chenforegone.encoreticket.security.config.SecurityConfig;
import io.github.chenforegone.encoreticket.security.token.AuthTokenStore;
import io.github.chenforegone.encoreticket.mapper.UserMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.sql.DataSource;
import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * HTTP 模拟事件适配、同步事务完成和权限契约。
 * @author c1342
 */
@WebMvcTest(value = AdminPaymentSimulationController.class, properties = "encore.payment.simulation.enabled=true")
@ActiveProfiles("test")
@Import({SecurityConfig.class, PaymentSimulationService.class, PaymentSimulationHttpTest.Fixture.class,
        io.github.chenforegone.encoreticket.security.handler.JsonAuthenticationEntryPoint.class,
        io.github.chenforegone.encoreticket.security.handler.JsonAccessDeniedHandler.class})
class PaymentSimulationHttpTest {
    @Autowired MockMvc mvc;
    @Autowired RecordingHandler handler;
    @MockitoBean AuthTokenStore tokens;
    @MockitoBean UserMapper users;

    @Test
    void adminEventUsesServerMoneyAndCompletesHandlerTransactionBefore200() throws Exception {
        handler.reset();
        mvc.perform(post("/admin/test/payments/events").with(user("20").roles("ADMIN"))
                        .contentType("application/json")
                        .content("{\"paymentNo\":\"P1\",\"attemptId\":\"old-attempt\",\"eventId\":\"event-1\",\"result\":\"SUCCESS\",\"amount\":0,\"currency\":\"USD\",\"provider\":\"evil\"}"))
                .andExpect(status().isOk());
        assertThat(handler.committed()).isTrue();
        assertThat(handler.outerTransaction()).isFalse();
        assertThat(handler.event()).isEqualTo(new PaymentChannelEvent("SIM", "event-1", "P1",
                "old-attempt", "SUCCESS", new BigDecimal("88.00"), "CNY", true));
    }

    @Autowired tools.jackson.databind.json.JsonMapper json;

    @Test
    void eventIdMustBeNonblankPrintableAscii() throws Exception {
        for (String eventId : java.util.List.of("", " ", "中文", "é", "event\n1", "event\t1", "event\u00001", "event\u007f")) {
            handler.reset();
            String body = json.writeValueAsString(java.util.Map.of("paymentNo", "P1", "attemptId", "a",
                    "eventId", eventId, "result", "SUCCESS"));
            mvc.perform(post("/admin/test/payments/events").with(user("20").roles("ADMIN"))
                    .contentType("application/json").content(body)).andExpect(status().isBadRequest());
            assertThat(handler.event()).isNull();
        }
        String printable = java.util.stream.IntStream.rangeClosed(32, 126)
                .collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append).toString();
        for (String eventId : java.util.List.of(printable, "e".repeat(128))) {
            mvc.perform(post("/admin/test/payments/events").with(user("20").roles("ADMIN"))
                    .contentType("application/json").content(json.writeValueAsString(java.util.Map.of(
                            "paymentNo", "P1", "attemptId", "a", "eventId", eventId, "result", "SUCCESS"))))
                    .andExpect(status().isOk());
            assertThat(handler.event().eventId()).isEqualTo(eventId);
        }
    }

    @Test
    void transactionCommitFailureNeverReturns200() throws Exception {
        handler.reset();
        handler.failCommit();
        try {
            mvc.perform(post("/admin/test/payments/events").with(user("20").roles("ADMIN"))
                    .contentType("application/json").content("{\"paymentNo\":\"P1\",\"attemptId\":\"a\",\"eventId\":\"e\",\"result\":\"SUCCESS\"}"))
                    .andExpect(status().isInternalServerError());
            assertThat(handler.committed()).isFalse();
        } finally {
            handler.reset();
        }
    }

    @Test
    void attemptsHttpReturnsLegacyAndRejectsInvalidBounds() throws Exception {
        mvc.perform(get("/admin/test/payments/P1/attempts").with(user("20").roles("ADMIN")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.items[0].attemptId").value("legacy-1"))
                .andExpect(jsonPath("$.data.items[0].current").value(true))
                .andExpect(jsonPath("$.data.items[0].createdAt").isEmpty());
        for (String limit : java.util.List.of("0", "-1", "101", "2147483647", "abc")) {
            mvc.perform(get("/admin/test/payments/P1/attempts").param("limit", limit).with(user("20").roles("ADMIN")))
                    .andExpect(status().isBadRequest());
        }
        for (String cursor : java.util.List.of("-1", "abc", "9223372036854775808", "")) {
            mvc.perform(get("/admin/test/payments/P1/attempts").param("cursor", cursor).with(user("20").roles("ADMIN")))
                    .andExpect(status().isBadRequest());
        }
    }

    @Test
    void bothEntrypointsRequireAdminAndMethodSecurityAlsoRejectsUser() throws Exception {
        for (String path : java.util.List.of("/admin/test/payments/P1/attempts", "/admin/test/payments/events")) {
            var anonymous = path.endsWith("events") ? post(path) : get(path);
            mvc.perform(anonymous).andExpect(status().isUnauthorized());
            var ordinary = path.endsWith("events") ? post(path) : get(path);
            mvc.perform(ordinary.with(user("20").roles("USER"))).andExpect(status().isForbidden());
        }
        var context = org.springframework.security.core.context.SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                "20", "", java.util.List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_USER"))));
        org.springframework.security.core.context.SecurityContextHolder.setContext(context);
        try {
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> controller.attempts("P1", 20, null))
                    .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> controller.event(
                    new io.github.chenforegone.encoreticket.simulation.payment.PaymentSimulationEventRequest("P1", "a", "e",
                            io.github.chenforegone.encoreticket.simulation.payment.PaymentSimulationEventRequest.Result.SUCCESS)))
                    .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        } finally {
            org.springframework.security.core.context.SecurityContextHolder.clearContext();
        }
    }

    @Autowired AdminPaymentSimulationController controller;

    @Test
    void invalidEventTargetsNeverReachHandlerAndFailedIsAdapted() throws Exception {
        for (String body : java.util.List.of("{}", "null", "{",
                "{\"paymentNo\":\"P1\",\"attemptId\":\"a\",\"eventId\":\"e\",\"result\":\"PENDING\"}",
                "{\"paymentNo\":\" \",\"attemptId\":\"a\",\"eventId\":\"e\",\"result\":\"SUCCESS\"}",
                "{\"paymentNo\":\"P1\",\"attemptId\":\"\",\"eventId\":\"e\",\"result\":\"SUCCESS\"}",
                "{\"paymentNo\":\"P1\",\"attemptId\":\"a\",\"eventId\":\"" + "e".repeat(129) + "\",\"result\":\"SUCCESS\"}")) {
            handler.reset();
            mvc.perform(post("/admin/test/payments/events").with(user("20").roles("ADMIN"))
                    .contentType("application/json").content(body)).andExpect(status().isBadRequest());
            assertThat(handler.event()).isNull();
        }
        mvc.perform(post("/admin/test/payments/events").with(user("20").roles("ADMIN"))
                .contentType("application/json").content("{\"paymentNo\":\"P1\",\"attemptId\":\"a\",\"eventId\":\"failed\",\"result\":\"FAILED\"}"))
                .andExpect(status().isOk());
        assertThat(handler.event().successful()).isFalse();
        assertThat(handler.event().eventType()).isEqualTo("FAILED");
    }

    /**
     * 隔离数据库与事务代理夹具。
     * @author c1342
     */
    @TestConfiguration
    @EnableTransactionManagement(proxyTargetClass = true)
    static class Fixture {
        @Bean DataSource dataSource() {
            return new DriverManagerDataSource("jdbc:h2:mem:sim-http;MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        }
        @Bean JdbcClient jdbcClient(DataSource source) {
            var jdbc = JdbcClient.create(source);
            jdbc.sql("CREATE TABLE IF NOT EXISTS payment(id BIGINT PRIMARY KEY, payment_no VARCHAR(64), attempt_id VARCHAR(64), amount DECIMAL(10,2), currency VARCHAR(3))").update();
            jdbc.sql("CREATE TABLE IF NOT EXISTS payment_audit(id BIGINT PRIMARY KEY, payment_id BIGINT, operation VARCHAR(64), remark VARCHAR(255), create_time TIMESTAMP)").update();
            jdbc.sql("MERGE INTO payment KEY(id) VALUES(1,'P1','legacy-1',88.00,'CNY')").update();
            return jdbc;
        }
        @Bean JdbcTransactionManager transactionManager(DataSource source) { return new JdbcTransactionManager(source); }
        @Bean RecordingHandler recordingHandler() { return new RecordingHandler(); }
    }

    /**
     * 已确认结果处理 seam 的事务性测试替身。
     * @author c1342
     */
    public static class RecordingHandler implements PaymentChannelResultHandler {
        PaymentChannelEvent event;
        boolean committed;
        boolean outerTransaction;
        boolean failCommit;
        public void reset() { committed = false; event = null; failCommit = false; }
        public void failCommit() { failCommit = true; }
        public boolean committed() { return committed; }
        public boolean outerTransaction() { return outerTransaction; }
        public PaymentChannelEvent event() { return event; }
        @Override
        @Transactional
        public PaymentVO handleChannelEvent(PaymentChannelEvent event) {
            this.event = event;
            outerTransaction = !org.springframework.transaction.interceptor.TransactionAspectSupport
                    .currentTransactionStatus().isNewTransaction();
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void beforeCommit(boolean readOnly) {
                    if (failCommit) throw new IllegalStateException("模拟事务提交失败");
                }
                @Override public void afterCommit() { committed = true; }
            });
            return null;
        }
    }
}
