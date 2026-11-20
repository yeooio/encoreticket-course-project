package io.github.chenforegone.encoreticket.security.token;

import java.util.List;
import java.util.Objects;

/**
 * Redis 中缓存的登录会话。
 *
 * <p>令牌本身不会写入该对象。Redis value 只包含用户标识和登录成功时的角色快照，
 * 过滤器据此构造当前请求的认证信息。
 *
 * @param userId 用户 ID
 * @param roles  登录时缓存的角色编码
 * @param authVersion 认证版本号
 * @author c1342
 */
public record AuthTokenSession(String userId, List<String> roles, Long authVersion) {

    public AuthTokenSession(String userId, List<String> roles) {
        this(userId, roles, null);
    }

    public AuthTokenSession {
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("userId must not be blank");
        }
        userId = userId.trim();
        roles = roles == null
                ? List.of()
                : roles.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(role -> !role.isEmpty())
                .distinct()
                .toList();
    }
}
