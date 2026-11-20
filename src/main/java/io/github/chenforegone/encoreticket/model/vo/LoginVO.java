package io.github.chenforegone.encoreticket.model.vo;

import java.util.List;

/**
 * 注册或登录成功后的会话信息。
 *
 * @param accessToken 不透明访问令牌
 * @param user        用户资料
 * @param roles       用户角色编码
 * @author c1342
 */
public record LoginVO(
        String accessToken,
        UserVO user,
        List<String> roles
) {
    @Override
    public String toString() {
        return "LoginVO[accessToken=<redacted>, user=" + user + ", roles=" + roles + "]";
    }
}
