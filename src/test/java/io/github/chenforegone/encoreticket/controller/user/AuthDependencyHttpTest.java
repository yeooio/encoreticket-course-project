package io.github.chenforegone.encoreticket.controller.user;

import io.github.chenforegone.encoreticket.converter.UserConverter;
import io.github.chenforegone.encoreticket.mapper.UserMapper;
import io.github.chenforegone.encoreticket.mapper.UserRoleMapper;
import io.github.chenforegone.encoreticket.security.config.SecurityConfig;
import io.github.chenforegone.encoreticket.security.handler.JsonAccessDeniedHandler;
import io.github.chenforegone.encoreticket.security.handler.JsonAuthenticationEntryPoint;
import io.github.chenforegone.encoreticket.security.token.AuthTokenStore;
import io.github.chenforegone.encoreticket.security.token.TokenGenerator;
import io.github.chenforegone.encoreticket.service.impl.AuthServiceImpl;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 认证HTTP过滤链与真实业务服务的依赖故障契约。
 *
 * @author c1342
 */
@WebMvcTest({UserAuthController.class, UserAccountController.class})
@Import({SecurityConfig.class, JsonAuthenticationEntryPoint.class, JsonAccessDeniedHandler.class,
        AuthServiceImpl.class, io.github.chenforegone.encoreticket.service.impl.UserServiceImpl.class,
        UserConverter.class, TokenGenerator.class})
class AuthDependencyHttpTest {
    @Autowired private MockMvc mvc;
    @MockitoBean private AuthTokenStore tokenStore;
    @MockitoBean private UserMapper userMapper;
    @MockitoBean private UserRoleMapper userRoleMapper;
    @MockitoBean private io.github.chenforegone.encoreticket.utils.DefaultProfileGenerator profiles;

    @Test
    void redisSessionForMissingUserIsUnauthorized() throws Exception {
        org.mockito.Mockito.when(tokenStore.findSessionByToken("current-token"))
                .thenReturn(new io.github.chenforegone.encoreticket.security.token.AuthTokenSession("42", java.util.List.of("USER")));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/users/me")
                        .header("Authorization", "Bearer current-token"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    void disabledDatabaseUserCannotUseRedisSession() throws Exception {
        var user = io.github.chenforegone.encoreticket.model.entity.User.register("alice", "hash", "Alice", "avatar");
        user.setId(42L);
        user.setAuthVersion(3L);
        user.setStatus(io.github.chenforegone.encoreticket.model.enums.UserStatus.DISABLED);
        org.mockito.Mockito.when(userMapper.selectById("42")).thenReturn(user);
        org.mockito.Mockito.when(tokenStore.findSessionByToken("current-token"))
                .thenReturn(new io.github.chenforegone.encoreticket.security.token.AuthTokenSession("42", java.util.List.of("USER"), 3L));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/users/me")
                        .header("Authorization", "Bearer current-token"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        org.mockito.Mockito.verify(userMapper).selectById("42");
        org.mockito.Mockito.verifyNoMoreInteractions(userMapper);
        user.setStatus(io.github.chenforegone.encoreticket.model.enums.UserStatus.NORMAL);
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/users/me")
                        .header("Authorization", "Bearer current-token"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.id").value("42"));
    }

    @Test
    void legacySessionWithoutVersionIsUnauthorized() throws Exception {
        var user = io.github.chenforegone.encoreticket.model.entity.User.register("alice", "hash", "Alice", "avatar");
        user.setId(42L);
        org.mockito.Mockito.when(userMapper.selectById("42")).thenReturn(user);
        org.mockito.Mockito.when(tokenStore.findSessionByToken("current-token"))
                .thenReturn(new io.github.chenforegone.encoreticket.security.token.AuthTokenSession("42", java.util.List.of("USER"), null));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/users/me")
                        .header("Authorization", "Bearer current-token"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        org.mockito.Mockito.verify(userMapper).selectById("42");
        org.mockito.Mockito.when(tokenStore.findSessionByToken("current-token"))
                .thenReturn(new io.github.chenforegone.encoreticket.security.token.AuthTokenSession("42", java.util.List.of("USER"), 0L));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/users/me")
                        .header("Authorization", "Bearer current-token"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.id").value("42"));
    }

    @Test
    void storedLegacyJsonReturns401WithoutDatabaseLookupOrRedisMutation() throws Exception {
        var redis = org.mockito.Mockito.mock(org.springframework.data.redis.core.StringRedisTemplate.class);
        org.springframework.data.redis.core.ValueOperations<String, String> values = org.mockito.Mockito.mock(
                org.springframework.data.redis.core.ValueOperations.class);
        org.mockito.Mockito.when(redis.opsForValue()).thenReturn(values);
        String key = io.github.chenforegone.encoreticket.common.constant.RedisConstant.USER_TOKEN
                + new io.github.chenforegone.encoreticket.security.token.TokenHasher().hash("legacy-token");
        org.mockito.Mockito.when(values.get(key)).thenReturn("{\"userId\":\"42\",\"roles\":[\"USER\"]}");
        var store = new io.github.chenforegone.encoreticket.security.token.RedisAuthTokenStore(redis);
        org.mockito.Mockito.when(tokenStore.findSessionByToken("legacy-token"))
                .thenAnswer(invocation -> store.findSessionByToken("legacy-token"));

        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/users/me")
                        .header("Authorization", "Bearer legacy-token"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
                .andExpect(header().exists("X-Request-Id"));
        verifyNoInteractions(userMapper, userRoleMapper);
        org.mockito.Mockito.verify(redis).opsForValue();
        org.mockito.Mockito.verify(values).get(key);
        org.mockito.Mockito.verifyNoMoreInteractions(redis, values);
    }

    @Test
    void passwordRevocationDependencyFailureReturns503InsteadOfSuccess() throws Exception {
        var user = io.github.chenforegone.encoreticket.model.entity.User.register("alice",
                new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder().encode("secret123"), "Alice", "avatar");
        user.setId(42L);
        org.mockito.Mockito.when(userMapper.selectById("42")).thenReturn(user);
        org.mockito.Mockito.when(userMapper.selectById(42L)).thenReturn(user);
        org.mockito.Mockito.when(userMapper.updatePasswordByCas(
                org.mockito.ArgumentMatchers.eq(42L), org.mockito.ArgumentMatchers.eq(0L),
                org.mockito.ArgumentMatchers.anyString())).thenReturn(1);
        org.mockito.Mockito.when(tokenStore.findSessionByToken("current-token"))
                .thenReturn(new io.github.chenforegone.encoreticket.security.token.AuthTokenSession("42", java.util.List.of("USER"), 0L));
        org.mockito.Mockito.doThrow(new org.springframework.data.redis.RedisConnectionFailureException("private"))
                .when(tokenStore).deleteAllTokensForUser("42");
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/users/me/password")
                        .header("Authorization", "Bearer current-token").contentType("application/json")
                        .content("{\"oldPassword\":\"secret123\",\"newPassword\":\"new12345\",\"confirmPassword\":\"new12345\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("SERVICE_UNAVAILABLE"))
                .andExpect(header().exists("X-Request-Id"));
    }

    @Test
    void profilePatchIgnoresUnverifiedContactsAndPreservesPassword() throws Exception {
        var user = io.github.chenforegone.encoreticket.model.entity.User.register("alice", "private-hash", "Alice", "avatar");
        user.setId(42L);
        user.setEmail("confirmed@example.com");
        org.mockito.Mockito.when(userMapper.selectById("42")).thenReturn(user);
        org.mockito.Mockito.when(tokenStore.findSessionByToken("current-token"))
                .thenReturn(new io.github.chenforegone.encoreticket.security.token.AuthTokenSession("42", java.util.List.of("USER"), 0L));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch("/users/me")
                        .header("Authorization", "Bearer current-token").contentType("application/json")
                        .content("{\"nickname\":\"New\",\"email\":\"unverified@example.com\",\"phone\":\"13800000000\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.email").value("confirmed@example.com"))
                .andExpect(jsonPath("$.data.phone").isEmpty());
        org.mockito.Mockito.verify(userMapper).updateById(org.mockito.ArgumentMatchers.argThat(
                (io.github.chenforegone.encoreticket.model.entity.User patch) -> patch.getPassword() == null
                        && patch.getEmail() == null && patch.getPhone() == null && patch.getAuthVersion() == null));
    }

    @Test
    void currentVersionWorksButOldVersionIsUnauthorized() throws Exception {
        var user = io.github.chenforegone.encoreticket.model.entity.User.register("alice", "hash", "Alice", "avatar");
        user.setId(42L);
        user.setAuthVersion(3L);
        org.mockito.Mockito.when(userMapper.selectById("42")).thenReturn(user);
        org.mockito.Mockito.when(tokenStore.findSessionByToken("current-token"))
                .thenReturn(new io.github.chenforegone.encoreticket.security.token.AuthTokenSession("42", java.util.List.of("USER"), 3L));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/users/me")
                        .header("Authorization", "Bearer current-token"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.id").value("42"));
        user.setAuthVersion(4L);
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/users/me")
                        .header("Authorization", "Bearer current-token"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void databaseConnectionFailureReturns503ButSqlProgrammingErrorRemains500() throws Exception {
        org.mockito.Mockito.when(tokenStore.findSessionByToken("current-token"))
                .thenReturn(new io.github.chenforegone.encoreticket.security.token.AuthTokenSession("42", java.util.List.of("USER")));
        org.mockito.Mockito.when(userMapper.selectById("42"))
                .thenThrow(new org.springframework.jdbc.CannotGetJdbcConnectionException("private-jdbc"));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/users/me")
                        .header("Authorization", "Bearer current-token"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("SERVICE_UNAVAILABLE"))
                .andExpect(jsonPath("$.message").value("服务暂时不可用，请稍后重试"))
                .andExpect(header().exists("X-Request-Id"));
        org.mockito.Mockito.doThrow(new org.springframework.jdbc.BadSqlGrammarException("private", "private-sql", new java.sql.SQLException()))
                .when(userMapper).selectById("42");
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/users/me")
                        .header("Authorization", "Bearer current-token"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void authenticationAndRevocationTimeoutsLogSafeCauseExactlyOnce(boolean revocation) throws Exception {
        var failure = new org.springframework.dao.QueryTimeoutException("private-query-secret",
                new java.sql.SQLException("private-jdbc-secret", "HYT00", 1205));
        org.mockito.Mockito.when(tokenStore.findSessionByToken("current-token"))
                .thenReturn(new io.github.chenforegone.encoreticket.security.token.AuthTokenSession("42", java.util.List.of("USER"), 0L));
        var request = org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/users/me");
        if (revocation) {
            var user = io.github.chenforegone.encoreticket.model.entity.User.register("alice",
                    new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder().encode("secret123"), "Alice", "avatar");
            user.setId(42L);
            org.mockito.Mockito.when(userMapper.selectById("42")).thenReturn(user);
            org.mockito.Mockito.when(userMapper.selectById(42L)).thenReturn(user);
            org.mockito.Mockito.when(userMapper.updatePasswordByCas(
                    org.mockito.ArgumentMatchers.eq(42L), org.mockito.ArgumentMatchers.eq(0L),
                    org.mockito.ArgumentMatchers.anyString())).thenReturn(1);
            org.mockito.Mockito.doThrow(failure).when(tokenStore).deleteAllTokensForUser("42");
            request = org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/users/me/password")
                    .contentType("application/json")
                    .content("{\"oldPassword\":\"secret123\",\"newPassword\":\"new12345\",\"confirmPassword\":\"new12345\"}");
        } else {
            org.mockito.Mockito.when(userMapper.selectById("42")).thenThrow(failure);
        }
        var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(
                io.github.chenforegone.encoreticket.common.exception.GlobalExceptionHandler.class);
        var appender = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        try {
            var result = mvc.perform(request.header("Authorization", "Bearer current-token"))
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.code").value("SERVICE_UNAVAILABLE"))
                    .andExpect(jsonPath("$.message").value("服务暂时不可用，请稍后重试"))
                    .andExpect(header().exists("X-Request-Id"))
                    .andReturn();
            org.assertj.core.api.Assertions.assertThat(appender.list).hasSize(1);
            var event = appender.list.getFirst();
            org.assertj.core.api.Assertions.assertThat(event.getFormattedMessage())
                    .contains(result.getResponse().getHeader("X-Request-Id"),
                            "DependencyUnavailableException", "Caused by: org.springframework.dao.QueryTimeoutException",
                            "Caused by: java.sql.SQLException", "sqlState=HYT00", "vendorCode=1205",
                            "path=" + (revocation ? "/users/me/password" : "/users/me"))
                    .doesNotContain("private-query-secret", "private-jdbc-secret", "current-token", "secret123", "new12345");
            org.assertj.core.api.Assertions.assertThat(event.getThrowableProxy()).isNull();
            org.assertj.core.api.Assertions.assertThat(result.getResponse().getContentAsString())
                    .doesNotContain("private-query-secret", "private-jdbc-secret", "current-token", "QueryTimeoutException");
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    @Test
    void registrationWriteFailureReturns503WithoutToken() throws Exception {
        org.mockito.Mockito.when(profiles.generateAvatar()).thenReturn("avatar");
        org.mockito.Mockito.doAnswer(invocation -> {
            ((io.github.chenforegone.encoreticket.model.entity.User) invocation.getArgument(0)).setId(42L);
            return 1;
        }).when(userMapper).insert(org.mockito.ArgumentMatchers.any(io.github.chenforegone.encoreticket.model.entity.User.class));
        org.mockito.Mockito.doThrow(new org.springframework.dao.QueryTimeoutException("private-token"))
                .when(tokenStore).saveToken(org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyLong());
        mvc.perform(post("/users/register").contentType("application/json")
                        .content("{\"username\":\"alice\",\"password\":\"secret123\",\"confirmPassword\":\"secret123\",\"nickname\":\"Alice\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("SERVICE_UNAVAILABLE"))
                .andExpect(jsonPath("$.message").value("服务暂时不可用，请稍后重试"))
                .andExpect(jsonPath("$.data").isEmpty())
                .andExpect(header().exists("X-Request-Id"));
    }

    @Test
    void redisConnectionFailureReturnsSafe503WithRequestId() throws Exception {
        org.mockito.Mockito.when(tokenStore.findSessionByToken("current-token"))
                .thenThrow(new org.springframework.data.redis.RedisConnectionFailureException("secret-token"));
        var result = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/auth/me")
                        .header("Authorization", "Bearer current-token"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("SERVICE_UNAVAILABLE"))
                .andExpect(jsonPath("$.message").value("服务暂时不可用，请稍后重试"))
                .andExpect(jsonPath("$.data").isEmpty())
                .andReturn();
        org.assertj.core.api.Assertions.assertThat(result.getResponse().getContentAsString())
                .doesNotContain("secret-token");
        org.assertj.core.api.Assertions.assertThat(result.getResponse().getHeader("X-Request-Id"))
                .isNotBlank();
        org.assertj.core.api.Assertions.assertThat(result.getResponse().getContentAsString())
                .contains(result.getResponse().getHeader("X-Request-Id"));
    }

    @Test
    void logoutRedisCleanupFailureStillSucceeds() throws Exception {
        org.mockito.Mockito.doThrow(new org.springframework.data.redis.RedisConnectionFailureException("private-token"))
                .when(tokenStore).deleteToken("current-token");
        var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(AuthServiceImpl.class);
        var appender = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        try {
            var result = mvc.perform(post("/auth/logout").header("Authorization", "Bearer current-token")
                            .contentType("application/json").content("{\"userId\":\"other-user\"}"))
                    .andExpect(status().isOk())
                    .andExpect(content().json("{\"data\":null}"))
                    .andReturn();
            org.assertj.core.api.Assertions.assertThat(appender.list.stream()
                    .map(ch.qos.logback.classic.spi.ILoggingEvent::getFormattedMessage)
                    .collect(java.util.stream.Collectors.joining()))
                    .contains(result.getResponse().getHeader("X-Request-Id"))
                    .doesNotContain("private-token", "current-token", "other-user");
        } finally {
            logger.detachAppender(appender);
        }
        org.mockito.Mockito.verify(tokenStore).deleteToken("current-token");
        org.mockito.Mockito.verifyNoMoreInteractions(tokenStore);
        verifyNoInteractions(userMapper, userRoleMapper);
    }

    @Test
    void timeoutAndUnexpectedFailuresKeepTheirDistinctContracts() throws Exception {
        for (RuntimeException failure : java.util.List.of(
                new org.springframework.dao.QueryTimeoutException("private-timeout"),
                new io.lettuce.core.RedisCommandTimeoutException("private-timeout"))) {
            org.mockito.Mockito.doThrow(failure).when(tokenStore).findSessionByToken("current-token");
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/auth/me")
                            .header("Authorization", "Bearer current-token"))
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.code").value("SERVICE_UNAVAILABLE"));
            org.mockito.Mockito.doThrow(failure).when(tokenStore).deleteToken("current-token");
            mvc.perform(post("/auth/logout").header("Authorization", "Bearer current-token"))
                    .andExpect(status().isOk()).andExpect(content().json("{\"data\":null}"));
        }
        org.mockito.Mockito.doThrow(new IllegalStateException("private-json"))
                .when(tokenStore).deleteToken("current-token");
        mvc.perform(post("/auth/logout").header("Authorization", "Bearer current-token"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"));
        org.mockito.Mockito.doThrow(new IllegalStateException("private-json"))
                .when(tokenStore).findSessionByToken("current-token");
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/auth/me")
                        .header("Authorization", "Bearer current-token"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"));
    }

    @Test
    void malformedLogoutHeadersNeverReachStorage() throws Exception {
        for (String header : java.util.List.of("", "Basic abc", "Bearer", "Bearer a b", "Bearer a!b", "Bearer a,b")) {
            mvc.perform(post("/auth/logout").header("Authorization", header))
                    .andExpect(status().isOk()).andExpect(content().json("{\"data\":null}"));
        }
        verifyNoInteractions(tokenStore, userMapper, userRoleMapper);
    }

    @Test
    void expiredLogoutDeletesOnlySubmittedTokenWithoutAuthentication() throws Exception {
        mvc.perform(post("/auth/logout").header("Authorization", "bearer expired-token"))
                .andExpect(status().isOk()).andExpect(content().json("{\"data\":null}"));
        org.mockito.Mockito.verify(tokenStore).deleteToken("expired-token");
        org.mockito.Mockito.verifyNoMoreInteractions(tokenStore);
        verifyNoInteractions(userMapper, userRoleMapper);
    }

    @Test
    void logoutExceptionDoesNotOpenOtherMethodsOrPaths() throws Exception {
        for (String path : java.util.List.of("/auth/logout", "/auth/me", "/auth/logout/extra", "/auth/logout/")) {
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(path))
                    .andExpect(status().isUnauthorized());
        }
        mvc.perform(post("/auth/logout/extra")).andExpect(status().isUnauthorized());
        mvc.perform(post("/auth/logout/")).andExpect(status().isUnauthorized());
        org.mockito.Mockito.when(tokenStore.findSessionByToken("current-token"))
                .thenThrow(new org.springframework.data.redis.RedisConnectionFailureException("private"));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/auth/logout")
                        .header("Authorization", "Bearer current-token"))
                .andExpect(status().isServiceUnavailable());
    }

    @Test
    void loginWriteFailureReturns503WithoutToken() throws Exception {
        var user = io.github.chenforegone.encoreticket.model.entity.User.register(
                "alice", new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder().encode("secret123"),
                "Alice", "avatar");
        user.setId(42L);
        org.mockito.Mockito.when(userMapper.selectOne(org.mockito.ArgumentMatchers.any())).thenReturn(user);
        org.mockito.Mockito.when(userMapper.update(
                org.mockito.ArgumentMatchers.any(io.github.chenforegone.encoreticket.model.entity.User.class),
                org.mockito.ArgumentMatchers.any())).thenReturn(1);
        org.mockito.Mockito.when(userRoleMapper.selectRoleCodesByUserId(42L)).thenReturn(java.util.List.of("USER"));
        org.mockito.Mockito.doThrow(new org.springframework.data.redis.RedisConnectionFailureException("private-token"))
                .when(tokenStore).saveToken(org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyLong());
        mvc.perform(post("/auth/login").contentType("application/json")
                        .content("{\"username\":\"alice\",\"password\":\"secret123\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("SERVICE_UNAVAILABLE"))
                .andExpect(jsonPath("$.data").isEmpty())
                .andExpect(jsonPath("$.accessToken").doesNotExist())
                .andExpect(header().exists("X-Request-Id"));
    }

    @Test
    void anonymousLogoutSucceedsWithoutStorageAccess() throws Exception {
        mvc.perform(post("/auth/logout"))
                .andExpect(status().isOk())
                .andExpect(content().json("{\"data\":null}"))
                .andExpect(header().exists("X-Request-Id"));
        verifyNoInteractions(tokenStore, userMapper, userRoleMapper);
    }
}
