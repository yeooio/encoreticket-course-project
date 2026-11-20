package io.github.chenforegone.encoreticket.security.token;

import io.github.chenforegone.encoreticket.common.constant.RedisConstant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.Duration;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Redis令牌存储公共接口测试。
 *
 * @author c1342
 */
class RedisAuthTokenStoreTest {

    private StringRedisTemplate redisTemplate;
    private ValueOperations<String, String> valueOperations;
    private SetOperations<String, String> setOperations;
    private RedisAuthTokenStore tokenStore;

    @BeforeEach
    void setUp() {
        redisTemplate = mock(StringRedisTemplate.class);
        valueOperations = mock(ValueOperations.class);
        setOperations = mock(SetOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(redisTemplate.opsForSet()).thenReturn(setOperations);
        tokenStore = new RedisAuthTokenStore(redisTemplate);
    }

    @Test
    @org.junit.jupiter.api.condition.EnabledIfSystemProperty(named = "auth.redis.enabled", matches = "true")
    void realRedisStoresVersionedDigestAndRevokesOnlyOwnedNamespace() {
        String password = System.getenv("AUTH_REDIS_PASSWORD");
        assertThat(password).isNotBlank();
        var config = new org.springframework.data.redis.connection.RedisStandaloneConfiguration("127.0.0.1",
                Integer.getInteger("auth.redis.port", 6380));
        config.setPassword(password);
        var factory = new org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory(config);
        factory.afterPropertiesSet();
        factory.start();
        var template = new StringRedisTemplate(factory);
        String owner = "auth_test_" + java.util.UUID.randomUUID();
        String token = new TokenGenerator().generate();
        String key = RedisConstant.USER_TOKEN + new TokenHasher().hash(token);
        String index = RedisConstant.USER_TOKEN_INDEX + owner;
        var real = new RedisAuthTokenStore(template);
        try {
            real.saveToken(owner, List.of("USER"), token, 5L);
            assertThat(real.findSessionByToken(token)).isEqualTo(new AuthTokenSession(owner, List.of("USER"), 5L));
            assertThat(template.opsForValue().get(key)).doesNotContain(token);
            assertThat(template.getExpire(key)).isBetween(604790L, 604800L);
            assertThat(real.findSessionByToken(new TokenHasher().hash(token))).isNull();
            real.deleteAllTokensForUser(owner);
            assertThat(real.findSessionByToken(token)).isNull();
            assertThat(template.hasKey(index)).isFalse();
        } finally {
            template.delete(List.of(key, index));
            factory.destroy();
        }
    }

    @Test
    void savesDigestKeyedSessionWithRolesTtlAndReverseIndex() {
        String token = "opaque-token";
        String digest = new TokenHasher().hash(token);

        tokenStore.saveToken("42", List.of("USER"), token, 0L);

        var script = org.mockito.ArgumentCaptor.forClass(RedisScript.class);
        verify(redisTemplate).execute(script.capture(),
                eq(List.of(RedisConstant.USER_TOKEN + digest, RedisConstant.USER_TOKEN_INDEX + "42")),
                eq("{\"userId\":\"42\",\"roles\":[\"USER\"],\"authVersion\":0}"), eq(digest), eq("604800000"));
        assertThat(script.getValue().getResultType()).isEqualTo(Long.class);
        assertThat(script.getValue().getScriptAsString()).isEqualTo("""
                local kind = redis.call('TYPE', KEYS[2]).ok
                if kind ~= 'none' and kind ~= 'set' then
                    return redis.error_reply('Invalid authentication index type')
                end
                redis.call('SADD', KEYS[2], ARGV[2])
                redis.call('PEXPIRE', KEYS[2], ARGV[3])
                redis.call('SET', KEYS[1], ARGV[1], 'PX', ARGV[3])
                return 1
                """);
    }

    @Test
    void persistsIssuedAuthenticationVersion() {
        String key = RedisConstant.USER_TOKEN + new TokenHasher().hash("versioned-token");
        tokenStore.saveToken("42", List.of("USER"), "versioned-token", 7L);
        var json = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(redisTemplate).execute(org.mockito.ArgumentMatchers.any(org.springframework.data.redis.core.script.RedisScript.class),
                eq(List.of(key, RedisConstant.USER_TOKEN_INDEX + "42")), json.capture(),
                eq(new TokenHasher().hash("versioned-token")), eq("604800000"));
        when(valueOperations.get(key)).thenReturn(json.getValue());
        assertThat(tokenStore.findSessionByToken("versioned-token").authVersion()).isEqualTo(7L);
    }

    @Test
    void findsSessionByHash() {
        String token = "opaque-token";
        String digest = new TokenHasher().hash(token);
        when(valueOperations.get(RedisConstant.USER_TOKEN + digest))
                .thenReturn("{\"userId\":\"42\",\"roles\":[\"ADMIN\"],\"authVersion\":0}");

        AuthTokenSession session = tokenStore.findSessionByToken(token);

        assertThat(session.authVersion()).isEqualTo(0L);
        assertThat(session.userId()).isEqualTo("42");
        verify(valueOperations).get(RedisConstant.USER_TOKEN + digest);
        verify(valueOperations, never()).get(RedisConstant.USER_TOKEN + token);
    }

    @Test
    void missingVersionIsInvalidWithoutRewritingOrRenewingStoredSession() {
        String token = "legacy-token";
        String key = RedisConstant.USER_TOKEN + new TokenHasher().hash(token);
        when(valueOperations.get(key)).thenReturn("{\"userId\":\"42\",\"roles\":[\"USER\"]}");

        assertThat(tokenStore.findSessionByToken(token)).isNull();
        verify(redisTemplate).opsForValue();
        verify(valueOperations).get(key);
        org.mockito.Mockito.verifyNoMoreInteractions(valueOperations, redisTemplate);
        org.mockito.Mockito.verifyNoInteractions(setOperations);
    }

    @Test
    void explicitNullAndInvalidVersionsAreNeverAcceptedAsLegacyZero() {
        String key = RedisConstant.USER_TOKEN + new TokenHasher().hash("invalid-version");
        for (String version : List.of("null", "-1", "1.5", "\"0\"", "true", "9223372036854775808")) {
            when(valueOperations.get(key)).thenReturn(
                    "{\"userId\":\"42\",\"roles\":[\"USER\"],\"authVersion\":" + version + "}");
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> tokenStore.findSessionByToken("invalid-version"))
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    @Test
    void deletesTokenAndRemovesItsReverseIndexMember() {
        String token = "opaque-token";
        String digest = new TokenHasher().hash(token);
        when(valueOperations.get(RedisConstant.USER_TOKEN + digest))
                .thenReturn("{\"userId\":\"42\",\"roles\":[],\"authVersion\":0}");

        tokenStore.deleteToken(token);

        var script = org.mockito.ArgumentCaptor.forClass(RedisScript.class);
        verify(redisTemplate).execute(script.capture(),
                eq(List.of(RedisConstant.USER_TOKEN + digest, RedisConstant.USER_TOKEN_INDEX + "42")), eq(digest));
        assertThat(script.getValue().getResultType()).isEqualTo(Long.class);
        assertThat(script.getValue().getScriptAsString()).isEqualTo("""
                redis.call('DEL', KEYS[1])
                redis.call('SREM', KEYS[2], ARGV[1])
                return 1
                """);
    }

    @Test
    void legacyLogoutDeletesOnlySubmittedTokenWithoutRenewal() {
        String token = "legacy-token";
        String key = RedisConstant.USER_TOKEN + new TokenHasher().hash(token);
        when(valueOperations.get(key)).thenReturn("{\"userId\":\"42\",\"roles\":[\"USER\"]}");

        tokenStore.deleteToken(token);

        verify(redisTemplate).opsForValue();
        verify(valueOperations).get(key);
        verify(redisTemplate).delete(key);
        org.mockito.Mockito.verifyNoMoreInteractions(redisTemplate, valueOperations);
        org.mockito.Mockito.verifyNoInteractions(setOperations);
    }

    @Test
    void partialWriteFailureIsCompensatedAndSafelyLogged() {
        var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(RedisAuthTokenStore.class);
        var appender = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        var request = new org.springframework.mock.web.MockHttpServletRequest("POST", "/auth/login");
        request.setAttribute(io.github.chenforegone.encoreticket.common.constant.RequestIdConstant.ATTRIBUTE_NAME, "req_write_failure");
        org.springframework.web.context.request.RequestContextHolder.setRequestAttributes(
                new org.springframework.web.context.request.ServletRequestAttributes(request));
        String digest = new TokenHasher().hash("private-token");
        var failure = new org.springframework.data.redis.RedisConnectionFailureException("private-token");
        org.mockito.Mockito.doThrow(failure).when(redisTemplate).execute(
                org.mockito.ArgumentMatchers.any(org.springframework.data.redis.core.script.RedisScript.class),
                org.mockito.ArgumentMatchers.anyList(), org.mockito.ArgumentMatchers.any(Object[].class));
        try {
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> tokenStore.saveToken("42", List.of("USER"), "private-token", 0L))
                    .isSameAs(failure);
            verify(redisTemplate).delete(RedisConstant.USER_TOKEN + digest);
            verify(setOperations).remove(RedisConstant.USER_TOKEN_INDEX + "42", digest);
            assertThat(appender.list).isNotEmpty();
            assertThat(appender.list.stream().map(ch.qos.logback.classic.spi.ILoggingEvent::getFormattedMessage)
                    .collect(java.util.stream.Collectors.joining())).contains("req_write_failure").doesNotContain("private-token");
        } finally {
            logger.detachAppender(appender);
            org.springframework.web.context.request.RequestContextHolder.resetRequestAttributes();
        }
    }

    @Test
    void corruptJsonRemainsProgramFailureAndExpiredTokenDeletionIsHarmless() {
        String key = RedisConstant.USER_TOKEN + new TokenHasher().hash("current-token");
        when(valueOperations.get(key)).thenReturn("{invalid-json");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> tokenStore.findSessionByToken("current-token"))
                .isInstanceOf(IllegalStateException.class);
        when(valueOperations.get(key)).thenReturn(null);
        tokenStore.deleteToken("current-token");
        verify(redisTemplate).delete(key);
        org.mockito.Mockito.verifyNoInteractions(setOperations);
    }

    @Test
    void cleanupFailureDoesNotReplaceOriginalWriteFailure() {
        String digest = new TokenHasher().hash("current-token");
        var failure = new org.springframework.dao.QueryTimeoutException("private-timeout");
        var cleanup = new org.springframework.data.redis.RedisConnectionFailureException("private-cleanup");
        org.mockito.Mockito.doThrow(failure).when(redisTemplate).execute(
                org.mockito.ArgumentMatchers.any(org.springframework.data.redis.core.script.RedisScript.class),
                org.mockito.ArgumentMatchers.anyList(), org.mockito.ArgumentMatchers.any(Object[].class));
        org.mockito.Mockito.doThrow(cleanup).when(redisTemplate).delete(RedisConstant.USER_TOKEN + digest);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> tokenStore.saveToken("42", List.of("USER"), "current-token", 0L))
                .isSameAs(failure).satisfies(exception -> assertThat(exception.getSuppressed()).containsExactly(cleanup));
    }

    @Test
    void deletesAllTokensFromUserReverseIndex() {
        String digest = new TokenHasher().hash("opaque-token");
        when(setOperations.members(RedisConstant.USER_TOKEN_INDEX + "42"))
                .thenReturn(Set.of(digest));

        tokenStore.deleteAllTokensForUser("42");

        var script = org.mockito.ArgumentCaptor.forClass(RedisScript.class);
        verify(redisTemplate).execute(script.capture(),
                eq(List.of(RedisConstant.USER_TOKEN_INDEX + "42")), eq(RedisConstant.USER_TOKEN));
        assertThat(script.getValue().getResultType()).isEqualTo(Long.class);
        assertThat(script.getValue().getScriptAsString()).isEqualTo("""
                local digests = redis.call('SMEMBERS', KEYS[1])
                for _, digest in ipairs(digests) do
                    redis.call('DEL', ARGV[1] .. digest)
                end
                redis.call('DEL', KEYS[1])
                return 1
                """);
    }
}
