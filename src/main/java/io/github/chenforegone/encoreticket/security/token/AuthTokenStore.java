package io.github.chenforegone.encoreticket.security.token;

import java.util.List;

/**
 * 认证令牌存储抽象。
 *
 * <p>维护登录令牌与用户 ID 之间的映射：登录时保存，鉴权时按令牌反查用户，登出时删除。
 * 具体存储介质由实现类决定，业务代码只依赖本接口，便于替换或测试替身。
 *
 * @author c1342
 */
public interface AuthTokenStore {

    /**
     * 根据令牌查询完整登录会话。
     *
     * @param token 客户端提交的原始令牌
     * @return 令牌对应的会话；令牌不存在或已失效时返回 {@code null}
     */
    AuthTokenSession findSessionByToken(String token);

    /**
     * 根据令牌查询对应用户 ID。
     *
     * @param token 认证令牌
     * @return 该令牌对应的用户 ID；令牌不存在或已失效时返回 {@code null}
     */
    default String findUserIdByToken(String token) {
        AuthTokenSession session = findSessionByToken(token);
        return session == null ? null : session.userId();
    }

    /**
     * 保存带角色快照及显式登录态版本的登录会话。
     *
     * <p>角色与版本必须来自一致的用户状态，不得使用缺省版本签发会话。
     *
     * @param userId 用户 ID
     * @param roles  登录时的角色编码
     * @param token  原始登录令牌
     * @param authVersion 与角色快照一致的登录态版本
     */
    void saveToken(String userId, List<String> roles, String token, Long authVersion);

    /**
     * 删除令牌，使该令牌立即失效。
     *
     * @param token 认证令牌
     */
    void deleteToken(String token);

    /**
     * 删除该用户的全部登录令牌，供禁用用户和修改密码时使用。
     *
     * @param userId 用户 ID
     */
    void deleteAllTokensForUser(String userId);
}
