package io.github.chenforegone.encoreticket.security.token;

import io.github.chenforegone.encoreticket.common.constant.RedisConstant;
import io.github.chenforegone.encoreticket.common.exception.ExceptionLogSupport;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

/**
 * 基于 Redis 的认证令牌存储实现。
 *
 * <p>以令牌的 SHA-256 摘要作为 Redis 键后缀，value 保存 {@link AuthTokenSession} JSON。
 * 每个令牌同时写入用户反向索引，便于修改密码或禁用账号时批量失效。
 *
 * @author c1342
 */
@Component
public class RedisAuthTokenStore implements AuthTokenStore {

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final TokenHasher tokenHasher;

    /** Spring 使用的完整构造器。 */
    @Autowired
    public RedisAuthTokenStore(StringRedisTemplate redisTemplate,
                               ObjectMapper objectMapper,
                               TokenHasher tokenHasher) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.tokenHasher = tokenHasher;
    }

    /** 兼容简单单元测试和旧调用方的构造器。 */
    public RedisAuthTokenStore(StringRedisTemplate redisTemplate) {
        this(redisTemplate, new ObjectMapper(), new TokenHasher());
    }

    /**
     * 按令牌读取完整登录会话。
     *
     * @param token 认证令牌
     * @return 令牌对应的会话；键不存在时返回 {@code null}
     */
    @Override
    public AuthTokenSession findSessionByToken(String token) {
        if (token == null || token.isBlank()) {
            return null;
        }
        String digest = tokenHasher.hash(token);
        String value = redisTemplate.opsForValue().get(RedisConstant.USER_TOKEN + digest);
        if (value == null) {
            return null;
        }
        try {
            var json = objectMapper.readTree(value);
            if (!json.has("authVersion")) {
                // 旧登录态按失效处理，不补版本、不改写 Redis 或延长 TTL。
                return null;
            }
            var version = json.get("authVersion");
            if (!version.isIntegralNumber() || !version.canConvertToLong() || version.longValue() < 0) {
                throw new IllegalStateException("Stored authentication version is invalid");
            }
            return objectMapper.readValue(value, AuthTokenSession.class);
        } catch (JacksonException exception) {
            throw new IllegalStateException("Stored authentication session is invalid", exception);
        }
    }

    /**
     * 按令牌读取用户 ID。
     *
     * @param token 认证令牌
     * @return 该令牌对应的用户 ID；键不存在时返回 {@code null}
     */
    @Override
    public String findUserIdByToken(String token) {
        AuthTokenSession session = findSessionByToken(token);
        return session == null ? null : session.userId();
    }

    /** 写入带角色快照及显式版本的登录会话，并设置固定七天 TTL。 */
    @Override
    public void saveToken(String userId, List<String> roles, String token, Long authVersion) {
        //通过SHA-256计算token生成摘要
        String digest = tokenHasher.hash(token);
        //构建反向索引,指向当前用户的所有token摘要
        String indexKey = RedisConstant.USER_TOKEN_INDEX + userId;
        String value;
        try {
            //将用户身份信息JSON化
            value = objectMapper.writeValueAsString(new AuthTokenSession(userId, roles, authVersion));
        } catch (JacksonException exception) {
            //序列化失败,抛出异常
            throw new IllegalStateException("Could not serialize authentication session", exception);
        }

        String tokenKey = RedisConstant.USER_TOKEN + digest;
        try {
            redisTemplate.execute(AuthLuaScript.SAVE, List.of(tokenKey, indexKey), value, digest,
                    Long.toString(RedisConstant.USER_TOKEN_TTL.toMillis()));
        } catch (RuntimeException exception) {
            // 保证中途 Redis 命令失败时不会留下未被用户反向索引的可用令牌。
            try {
                redisTemplate.delete(tokenKey);
                redisTemplate.opsForSet().remove(indexKey, digest);
            } catch (RuntimeException cleanupException) {
                exception.addSuppressed(cleanupException);
            }
            var attributes = RequestContextHolder.getRequestAttributes();
            if (attributes instanceof ServletRequestAttributes servlet) {
                ExceptionLogSupport.logOnce(
                        LoggerFactory.getLogger(RedisAuthTokenStore.class), servlet.getRequest(),
                        "登录态保存失败，已尝试清理部分写入", exception);
            }
            throw exception;
        }
    }

    /** 删除令牌及其用户反向索引成员，使该令牌立即失效。 */
    @Override
    public void deleteToken(String token) {
        if (token == null || token.isBlank()) {
            return;
        }
        String digest = tokenHasher.hash(token);
        AuthTokenSession session = findSessionByToken(token);
        if (session == null) {
            redisTemplate.delete(RedisConstant.USER_TOKEN + digest);
        } else {
            redisTemplate.execute(AuthLuaScript.REVOKE, List.of(RedisConstant.USER_TOKEN + digest,
                    RedisConstant.USER_TOKEN_INDEX + session.userId()), digest);
        }
    }

    /** 通过用户反向索引删除该用户的全部令牌。 */
    @Override
    public void deleteAllTokensForUser(String userId) {
        redisTemplate.execute(AuthLuaScript.REVOKE_ALL, List.of(RedisConstant.USER_TOKEN_INDEX + userId),
                RedisConstant.USER_TOKEN);
    }
}
