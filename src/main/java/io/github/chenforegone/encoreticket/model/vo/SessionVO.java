package io.github.chenforegone.encoreticket.model.vo;

import java.util.List;

/**
 * 当前登录会话信息（不含令牌）。
 *
 * <p>角色为登录时的快照，与令牌绑定；管理员后续修改角色不影响当前会话。
 *
 * @param user  当前用户资料
 * @param roles 登录时的角色编码列表
 * @author c1342
 */
public record SessionVO(
        UserVO user,
        List<String> roles
) {
}
