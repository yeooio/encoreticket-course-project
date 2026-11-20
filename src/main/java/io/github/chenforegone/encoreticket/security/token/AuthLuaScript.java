package io.github.chenforegone.encoreticket.security.token;

import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;

/**
 * 认证令牌存储使用的 Lua 脚本。
 *
 * @author c1342
 */
public final class AuthLuaScript {

    private static final String SCRIPT_BASE_PATH = "redis/auth/";

    public static final RedisScript<Long> SAVE = loadScript("save.lua");
    public static final RedisScript<Long> REVOKE_ALL = loadScript("revoke-all.lua");
    public static final RedisScript<Long> REVOKE = loadScript("revoke.lua");

    private AuthLuaScript() {
    }

    /** 加载 Lua 脚本，返回值类型固定为 Long。 */
    private static RedisScript<Long> loadScript(String filename) {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource(SCRIPT_BASE_PATH + filename));
        script.setResultType(Long.class);
        return script;
    }
}
