package io.github.chenforegone.encoreticket.converter;

import io.github.chenforegone.encoreticket.model.entity.User;
import io.github.chenforegone.encoreticket.model.enums.UserStatus;
import io.github.chenforegone.encoreticket.model.vo.UserVO;
import org.springframework.stereotype.Component;

import java.util.Objects;

/**
 * 用户模型转换器。
 *
 * <p>实体不直接作为接口响应，统一在本层完成主键字符串化与状态字符串化。
 *
 * @author c1342
 */
@Component
public class UserConverter {

    /**
     * 将用户实体转换为对外视图。
     *
     * @param user 用户实体
     * @return 用户视图；入参为 {@code null} 时返回 {@code null}
     */
    public UserVO toVO(User user) {
        if (user == null) {
            return null;
        }
        return new UserVO(
                Objects.toString(user.getId(), null),
                user.getUsername(),
                user.getNickname(),
                user.getAvatar(),
                user.getEmail(),
                user.getPhone(),
                toStatusApiCode(user.getStatus()),
                user.getCreateTime(),
                user.getUpdateTime()
        );
    }

    private String toStatusApiCode(UserStatus status) {
        return status == null ? null : status.getApiCode();
    }
}
