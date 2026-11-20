package io.github.chenforegone.encoreticket.common.constant;

import java.time.Duration;

/**
 * Redis 键前缀常量。
 *
 * <p>集中定义键前缀，避免各处在代码中硬编码字符串导致读写键不一致。
 *
 * @author c1342
 */
public final class RedisConstant {

    /**
     * 登录令牌键前缀。
     *
     * <p>完整键为 {@code auth:token:<sha256>}，值为令牌会话 JSON。
     */
    public static final String USER_TOKEN = "auth:token:";

    /** 用户令牌反向索引键前缀。 */
    public static final String USER_TOKEN_INDEX = "auth:user:tokens:";

    /** 登录令牌的固定绝对有效期。 */
    public static final Duration USER_TOKEN_TTL = Duration.ofDays(7);

    /** 登录令牌有效期的秒数。 */
    public static final long USER_TOKEN_TTL_SECONDS = USER_TOKEN_TTL.toSeconds();

    private RedisConstant() {
    }
}
