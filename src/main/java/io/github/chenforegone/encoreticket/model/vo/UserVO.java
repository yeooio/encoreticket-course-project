package io.github.chenforegone.encoreticket.model.vo;

import java.time.Instant;

/**
 * 用户信息视图对象。
 *
 * <p>主键以字符串返回以避免前端数值精度丢失，状态返回业务字符串而非数据库数字，
 * 不包含密码等敏感字段。
 *
 * @param id         用户主键，字符串形式
 * @param username   登录用户名
 * @param nickname   用户昵称
 * @param avatar     头像地址
 * @param email      电子邮箱，未填写时为 null
 * @param phone      手机号码，未填写时为 null
 * @param status     用户状态字符串，取值为 NORMAL 或 DISABLED
 * @param createTime 创建时间
 * @param updateTime 最后更新时间
 * @author c1342
 */
public record UserVO(
        String id,
        String username,
        String nickname,
        String avatar,
        String email,
        String phone,
        String status,
        Instant createTime,
        Instant updateTime
) {
}
