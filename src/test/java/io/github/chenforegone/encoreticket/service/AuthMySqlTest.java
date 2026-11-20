package io.github.chenforegone.encoreticket.service;

import com.baomidou.mybatisplus.spring.MybatisSqlSessionFactoryBean;
import io.github.chenforegone.encoreticket.config.MybatisPlusMetaObjectHandler;
import io.github.chenforegone.encoreticket.converter.UserConverter;
import io.github.chenforegone.encoreticket.mapper.UserMapper;
import io.github.chenforegone.encoreticket.model.dto.*;
import io.github.chenforegone.encoreticket.model.vo.LoginVO;
import io.github.chenforegone.encoreticket.security.token.*;
import io.github.chenforegone.encoreticket.service.impl.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.mock.web.MockServletContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.security.crypto.password.PasswordEncoder;
import tools.jackson.databind.ObjectMapper;
import javax.sql.DataSource;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;

/**
 * 隔离 MySQL 上的真实 Spring 事务、Flyway 升级与认证公开边界验证，不加载应用或调度器。
 *
 * @author c1342
 */
@EnabledIfSystemProperty(named = "auth.mysql.enabled", matches = "true")
class AuthMySqlTest {
    static AnnotationConfigWebApplicationContext context;
    static JdbcTemplate jdbc;
    static AuthService auth;
    static UserService users;
    static AuthTokenStore store;
    static MockMvc mvc;
    static PasswordEncoder encoder;
    static Flyway flyway;
    static final Map<String, AuthTokenSession> sessions = new ConcurrentHashMap<>();

    @BeforeAll
    static void start() throws Exception {
        String password = System.getenv("AUTH_MYSQL_PASSWORD");
        assertThat(password).as("显式启用实库验证时必须提供密码").isNotBlank();
        String server = System.getProperty("auth.mysql.server", "jdbc:mysql://127.0.0.1:3308/");
        assertThat(server).matches("jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/");
        String database = "encore_ticket_auth_test_" + UUID.randomUUID().toString().replace("-", "");
        var admin = new DriverManagerDataSource(server + "?connectionTimeZone=UTC", "root", password);
        new JdbcTemplate(admin).execute("CREATE DATABASE " + database);
        var ds = new DriverManagerDataSource(server + database + "?connectionTimeZone=UTC", "root", password);
        jdbc = new JdbcTemplate(ds);
        Flyway.configure().dataSource(ds).target("27").cleanDisabled(true).load().migrate();
        jdbc.update("INSERT INTO sys_user(id,username,password,status,create_time,update_time) VALUES(1,'premigration','unused',0,UTC_TIMESTAMP(),UTC_TIMESTAMP())");
        flyway = Flyway.configure().dataSource(ds).cleanDisabled(true).load();
        flyway.migrate();
        assertThat(jdbc.queryForObject("SELECT auth_version FROM sys_user WHERE id=1", Long.class)).isZero();
        flyway.validate();
        assertThat(flyway.migrate().migrationsExecuted).isZero();
        jdbc.update("DELETE FROM sys_user WHERE id=1");
        context = new AnnotationConfigWebApplicationContext();
        context.setServletContext(new MockServletContext());
        context.register(TestConfig.class);
        context.addBeanFactoryPostProcessor(factory -> factory.registerSingleton("dataSource", ds));
        context.refresh();
        users = context.getBean(UserService.class);
        auth = context.getBean(AuthService.class);
        store = context.getBean(AuthTokenStore.class);
        encoder = context.getBean(PasswordEncoder.class);
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @AfterAll
    static void stop() {
        try {
            if (jdbc != null) {
                jdbc.update("DELETE FROM user_role");
                jdbc.update("DELETE FROM sys_user");
            }
        } finally {
            if (context != null) context.close();
        }
    }

    @BeforeEach
    void storage() {
        reset(store, encoder);
        sessions.clear();
        jdbc.update("DELETE FROM user_role");
        jdbc.update("DELETE FROM sys_user");
        doAnswer(call -> {
            sessions.put(call.getArgument(2), new AuthTokenSession(call.getArgument(0), call.getArgument(1), call.getArgument(3)));
            return null;
        }).when(store).saveToken(anyString(), anyList(), anyString(), anyLong());
        when(store.findSessionByToken(anyString())).thenAnswer(call -> sessions.get(call.getArgument(0)));
        doAnswer(call -> { sessions.remove(call.getArgument(0)); return null; }).when(store).deleteToken(anyString());
        doAnswer(call -> { sessions.entrySet().removeIf(e -> e.getValue().userId().equals(call.getArgument(0))); return null; })
                .when(store).deleteAllTokensForUser(anyString());
    }

    static RegisterRequest registration(String name) {
        return new RegisterRequest(name, "secret123", "secret123", "测试用户", null, null, null);
    }

    @Test
    void registerAndLoginTokensReachProtectedSelfAndPasswordChangeRejectsOldSessions() throws Exception {
        var result = mvc.perform(post("/users/register").contentType("application/json")
                        .content("{\"username\":\"alice\",\"password\":\"secret123\",\"confirmPassword\":\"secret123\",\"nickname\":\"测试用户\"}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.data.roles[0]").value("USER")).andReturn();
        var json = new ObjectMapper().readTree(result.getResponse().getContentAsString());
        String first = json.at("/data/accessToken").asText();
        String id = json.at("/data/user/id").asText();
        mvc.perform(get("/users/me").header("Authorization", "Bearer " + first))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.id").value(id));
        var login = mvc.perform(post("/auth/login").contentType("application/json")
                        .content("{\"username\":\"alice\",\"password\":\"secret123\"}"))
                .andExpect(status().isOk()).andReturn();
        String second = new ObjectMapper().readTree(login.getResponse().getContentAsString()).at("/data/accessToken").asText();
        mvc.perform(get("/auth/me").header("Authorization", "Bearer " + second))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.user.id").value(id));
        var stale = sessions.get(first);
        mvc.perform(put("/users/me/password").header("Authorization", "Bearer " + first).contentType("application/json")
                        .content("{\"oldPassword\":\"secret123\",\"newPassword\":\"changed123\",\"confirmPassword\":\"changed123\"}"))
                .andExpect(status().isOk());
        sessions.put(first, stale);
        for (String token : List.of(first, second)) {
            mvc.perform(get("/users/me").header("Authorization", "Bearer " + token)).andExpect(status().isUnauthorized());
        }
        assertThat(auth.login(new LoginRequest("alice", "changed123")).accessToken()).isNotBlank();
    }

    @Test
    void migratedVersionDefaultsToZeroAndRejectsNegativeOrNullValues() {
        var user = users.register(registration("migration"));
        assertThat(jdbc.queryForObject("SELECT auth_version FROM sys_user WHERE id=?", Long.class, user.user().id())).isZero();
        assertThatThrownBy(() -> jdbc.update("UPDATE sys_user SET auth_version=-1 WHERE id=?", user.user().id()))
                .isInstanceOf(org.springframework.dao.DataAccessException.class)
                .satisfies(error -> assertThat(((java.sql.SQLException) error.getCause()).getErrorCode()).isEqualTo(3819));
        assertThatThrownBy(() -> jdbc.update("UPDATE sys_user SET auth_version=NULL WHERE id=?", user.user().id()))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    @Test
    void loginDuringUncommittedPasswordChangeReturnsTokenRejectedAfterCommit() throws Exception {
        var user = users.register(registration("changefirst"));
        CountDownLatch revoking = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch logging = new CountDownLatch(1);
        doAnswer(call -> {
            revoking.countDown();
            assertThat(release.await(10, TimeUnit.SECONDS)).isTrue();
            return null;
        }).when(store).deleteAllTokensForUser(user.user().id());
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> change = executor.submit(() -> users.changePassword(user.user().id(),
                    new ChangePasswordRequest("secret123", "changed123", "changed123")));
            assertThat(revoking.await(10, TimeUnit.SECONDS)).isTrue();
            Future<LoginVO> login = executor.submit(() -> {
                logging.countDown();
                return auth.login(new LoginRequest("changefirst", "secret123"));
            });
            assertThat(logging.await(10, TimeUnit.SECONDS)).isTrue();
            LoginVO issued = login.get(10, TimeUnit.SECONDS);
            assertThat(sessions.get(issued.accessToken()).authVersion()).isZero();
            release.countDown();
            change.get(10, TimeUnit.SECONDS);
            mvc.perform(get("/users/me").header("Authorization", "Bearer " + issued.accessToken()))
                    .andExpect(status().isUnauthorized());
            assertThat(auth.login(new LoginRequest("changefirst", "changed123")).accessToken()).isNotBlank();
        } finally {
            release.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void redisRevocationFailureRollsBackPasswordAndVersionInRealTransaction(boolean timeout) {
        LoginVO user = users.register(registration("rollback"));
        String id = user.user().id();
        String hash = jdbc.queryForObject("SELECT password FROM sys_user WHERE id=?", String.class, id);
        RuntimeException failure = timeout ? new org.springframework.dao.QueryTimeoutException("模拟超时")
                : new org.springframework.data.redis.RedisConnectionFailureException("模拟存储故障");
        doAnswer(call -> {
            assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            assertThat(jdbc.queryForObject("SELECT auth_version FROM sys_user WHERE id=?", Long.class, id)).isEqualTo(1L);
            assertThat(encoder.matches("changed123",
                    jdbc.queryForObject("SELECT password FROM sys_user WHERE id=?", String.class, id))).isTrue();
            throw failure;
        }).when(store).deleteAllTokensForUser(id);
        assertThatThrownBy(() -> users.changePassword(id, new ChangePasswordRequest("secret123", "changed123", "changed123")))
                .isInstanceOf(timeout ? io.github.chenforegone.encoreticket.common.exception.DependencyUnavailableException.class
                        : org.springframework.data.redis.RedisConnectionFailureException.class);
        assertThat(jdbc.queryForObject("SELECT password FROM sys_user WHERE id=?", String.class, id)).isEqualTo(hash);
        assertThat(jdbc.queryForObject("SELECT auth_version FROM sys_user WHERE id=?", Long.class, id)).isZero();
        assertThat(auth.login(new LoginRequest("rollback", "secret123")).accessToken()).isNotBlank();
        assertThatThrownBy(() -> auth.login(new LoginRequest("rollback", "changed123")))
                .isInstanceOf(io.github.chenforegone.encoreticket.common.exception.AuthenticationFailureException.class);
    }

    @Test
    void redisRegistrationFailureRollsBackUserAndRoleAndCleansPartialSession() {
        AtomicReference<String> token = new AtomicReference<>();
        doAnswer(call -> {
            token.set(call.getArgument(2));
            sessions.put(call.getArgument(2), new AuthTokenSession(call.getArgument(0), call.getArgument(1), call.getArgument(3)));
            throw new org.springframework.data.redis.RedisConnectionFailureException("模拟部分写入");
        }).when(store).saveToken(anyString(), anyList(), anyString(), anyLong());
        assertThatThrownBy(() -> users.register(registration("failed")))
                .isInstanceOf(org.springframework.data.redis.RedisConnectionFailureException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sys_user", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM user_role", Integer.class)).isZero();
        assertThat(sessions).isEmpty();
        verify(store).deleteToken(token.get());
    }

    @Test
    void loginSavingOldSnapshotAfterPasswordChangeReturnsImmediatelyInvalidToken() throws Exception {
        LoginVO user = users.register(registration("concurrent"));
        String id = user.user().id();
        CountDownLatch signing = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch changing = new CountDownLatch(1);
        doAnswer(call -> {
            signing.countDown();
            assertThat(release.await(10, TimeUnit.SECONDS)).isTrue();
            sessions.put(call.getArgument(2), new AuthTokenSession(call.getArgument(0), call.getArgument(1), call.getArgument(3)));
            return null;
        }).when(store).saveToken(anyString(), anyList(), anyString(), anyLong());
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<LoginVO> login = executor.submit(() -> auth.login(new LoginRequest("concurrent", "secret123")));
            assertThat(signing.await(10, TimeUnit.SECONDS)).isTrue();
            Future<?> change = executor.submit(() -> {
                changing.countDown();
                users.changePassword(id, new ChangePasswordRequest("secret123", "changed123", "changed123"));
            });
            assertThat(changing.await(10, TimeUnit.SECONDS)).isTrue();
            change.get(10, TimeUnit.SECONDS);
            release.countDown();
            LoginVO issued = login.get(10, TimeUnit.SECONDS);
            assertThat(sessions.get(issued.accessToken()).authVersion()).isZero();
            mvc.perform(get("/users/me").header("Authorization", "Bearer " + issued.accessToken())).andExpect(status().isUnauthorized());
            assertThat(jdbc.queryForObject("SELECT auth_version FROM sys_user WHERE id=?", Long.class, id)).isEqualTo(1L);
            assertThatThrownBy(() -> auth.login(new LoginRequest("concurrent", "secret123")))
                    .isInstanceOf(io.github.chenforegone.encoreticket.common.exception.AuthenticationFailureException.class);
        } finally {
            release.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void twoPasswordChangesFromSameVersionHaveExactlyOneWinner() throws Exception {
        LoginVO user = users.register(registration("casrace"));
        LoginVO other = users.register(registration("untouched"));
        String id = user.user().id();
        CyclicBarrier validated = new CyclicBarrier(2);
        doAnswer(call -> {
            // 两个请求均已完成普通读取和旧密码校验，再放行真实编码及条件 SQL。
            validated.await(10, TimeUnit.SECONDS);
            return call.callRealMethod();
        }).when(encoder).encode(startsWith("winner"));
        clearInvocations(store);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            List<Future<Throwable>> attempts = new ArrayList<>();
            for (String password : List.of("winner123", "winner456")) {
                attempts.add(executor.submit(() -> catchThrowable(() -> users.changePassword(id,
                        new ChangePasswordRequest("secret123", password, password)))));
            }
            List<Throwable> outcomes = new ArrayList<>();
            for (Future<Throwable> attempt : attempts) outcomes.add(attempt.get(15, TimeUnit.SECONDS));
            assertThat(outcomes.stream().filter(Objects::isNull).count()).isEqualTo(1);
            assertThat(outcomes.stream().filter(Objects::nonNull).toList()).singleElement().satisfies(error -> {
                assertThat(error).isInstanceOf(io.github.chenforegone.encoreticket.common.exception.ApplicationException.class);
                assertThat(((io.github.chenforegone.encoreticket.common.exception.ApplicationException) error).getErrorCode())
                        .isEqualTo(io.github.chenforegone.encoreticket.common.error.ErrorCode.RESOURCE_CONFLICT);
            });
            assertThat(jdbc.queryForObject("SELECT auth_version FROM sys_user WHERE id=?", Long.class, id)).isEqualTo(1L);
            String hash = jdbc.queryForObject("SELECT password FROM sys_user WHERE id=?", String.class, id);
            assertThat(List.of("winner123", "winner456").stream().filter(value -> encoder.matches(value, hash)).count())
                    .isEqualTo(1);
            assertThatThrownBy(() -> auth.login(new LoginRequest("casrace", "secret123")))
                    .isInstanceOf(io.github.chenforegone.encoreticket.common.exception.AuthenticationFailureException.class);
            String winner = encoder.matches("winner123", hash) ? "winner123" : "winner456";
            assertThat(auth.login(new LoginRequest("casrace", winner)).accessToken()).isNotBlank();
            assertThat(auth.login(new LoginRequest("untouched", "secret123")).user().id()).isEqualTo(other.user().id());
            assertThat(jdbc.queryForObject("SELECT auth_version FROM sys_user WHERE id=?", Long.class, other.user().id())).isZero();
            verify(store).deleteAllTokensForUser(id);
            verify(store, times(1)).deleteAllTokensForUser(anyString());
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Configuration
    @EnableWebMvc
    @org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
    @EnableTransactionManagement
    @MapperScan(basePackageClasses = UserMapper.class, annotationClass = org.apache.ibatis.annotations.Mapper.class)
    @Import({AuthServiceImpl.class, UserServiceImpl.class, UserConverter.class, TokenGenerator.class,
            io.github.chenforegone.encoreticket.controller.user.UserAuthController.class,
            io.github.chenforegone.encoreticket.controller.user.UserAccountController.class,
            io.github.chenforegone.encoreticket.security.config.SecurityConfig.class,
            io.github.chenforegone.encoreticket.security.filter.RedisTokenAuthenticationFilter.class,
            io.github.chenforegone.encoreticket.security.filter.RequestIdFilter.class,
            io.github.chenforegone.encoreticket.security.filter.ExceptionBridgeFilter.class,
            io.github.chenforegone.encoreticket.security.handler.JsonAuthenticationEntryPoint.class,
            io.github.chenforegone.encoreticket.security.handler.JsonAccessDeniedHandler.class,
            io.github.chenforegone.encoreticket.common.exception.GlobalExceptionHandler.class})
    static class TestConfig {
        @Bean static org.springframework.beans.factory.config.BeanPostProcessor encodingBarrierSupport() {
            return new org.springframework.beans.factory.config.BeanPostProcessor() {
                @Override
                public Object postProcessAfterInitialization(Object bean, String name) {
                    return bean instanceof PasswordEncoder ? spy(bean) : bean;
                }
            };
        }
        @Bean ObjectMapper objectMapper() { return new ObjectMapper(); }
        @Bean AuthTokenStore tokenStore() { return mock(AuthTokenStore.class); }
        @Bean io.github.chenforegone.encoreticket.utils.DefaultProfileGenerator profiles() {
            return new io.github.chenforegone.encoreticket.utils.DefaultProfileGenerator();
        }
        @Bean PlatformTransactionManager transactionManager(DataSource ds) { return new DataSourceTransactionManager(ds); }
        @Bean org.apache.ibatis.session.SqlSessionFactory sqlSessionFactory(DataSource ds) throws Exception {
            var factory = new MybatisSqlSessionFactoryBean();
            factory.setDataSource(ds);
            var global = new com.baomidou.mybatisplus.core.config.GlobalConfig();
            global.setMetaObjectHandler(new MybatisPlusMetaObjectHandler());
            factory.setGlobalConfig(global);
            return factory.getObject();
        }
    }
}
