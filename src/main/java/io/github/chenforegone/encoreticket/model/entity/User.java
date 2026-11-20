package io.github.chenforegone.encoreticket.model.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.github.chenforegone.encoreticket.model.enums.UserStatus;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import java.time.Instant;

/**
 * 系统用户实体，对应 {@code sys_user} 表。
 *
 * <p>密码字段保存 BCrypt 密文，禁止输出到接口响应或日志，Lombok 生成的
 * {@code toString} 已排除该字段。主键由应用生成，创建时间与更新时间由
 * MyBatis-Plus 在插入与更新时自动填充。
 *
 * @author c1342
 */
@Getter
@Setter
@ToString(exclude = "password")
@TableName("sys_user")
public class User {

    /** 用户主键。 */
    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private Long id;

    /** 登录用户名，落库前统一转为小写。 */
    private String username;

    /** 密码密文，不保存明文。 */
    private String password;

    /** 用户昵称。 */
    private String nickname;

    /** 头像地址。 */
    private String avatar;

    /** 电子邮箱，未填写时为 null。 */
    private String email;

    /** 手机号码，未填写时为 null。 */
    private String phone;

    /** 用户状态，数据库保存 TINYINT。 */
    private UserStatus status;

    /** 登录态版本，敏感身份变更时在同一事务内推进。 */
    private Long authVersion;

    /** 创建时间，按 UTC 保存。 */
    @TableField(value = "create_time", fill = FieldFill.INSERT)
    private Instant createTime;

    /** 最后更新时间，按 UTC 保存。 */
    @TableField(value = "update_time", fill = FieldFill.INSERT_UPDATE)
    private Instant updateTime;

    /**
     * 创建状态正常的新用户。
     *
     * <p>只做字段装配：状态固定为 {@link UserStatus#NORMAL}，主键与审计时间留给
     * MyBatis-Plus 填充。格式校验、唯一性校验、密码编码和随机昵称/头像的生成都由
     * Service 负责，本方法不查库、不校验、不使用随机源，便于单测。
     *
     * <p>邮箱与手机号是允许为 {@code null} 的可选业务数据，由调用方在需要时通过
     * setter 补充，不放进本方法，避免连续多个同类型字符串参数被写错位置。
     *
     * @param username        登录用户名，调用方需已规范化为小写
     * @param encodedPassword 密码密文
     * @param nickname        用户昵称
     * @param avatar          头像地址
     * @return 字段完备的新用户实例
     */
    public static User register(String username, String encodedPassword, String nickname, String avatar) {
        User user = new User();
        user.setUsername(username);
        user.setPassword(encodedPassword);
        user.setNickname(nickname);
        user.setAvatar(avatar);
        user.setStatus(UserStatus.NORMAL);
        user.setAuthVersion(0L);
        return user;
    }
}
