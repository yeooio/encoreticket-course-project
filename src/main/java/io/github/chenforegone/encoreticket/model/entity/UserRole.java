package io.github.chenforegone.encoreticket.model.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * 用户角色关系实体，对应 {@code user_role} 表。
 *
 * <p>一个用户可以拥有多个角色，关系由 Service 层维护。
 *
 * @author c1342
 */
@Getter
@Setter
@TableName("user_role")
public class UserRole {

    /** 关系主键。 */
    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private Long id;

    /** 用户 ID，逻辑关联 sys_user.id。 */
    private Long userId;

    /** 角色 ID，逻辑关联 role.id。 */
    private Long roleId;

    /** 创建时间，按 UTC 保存。 */
    @TableField(value = "create_time", fill = FieldFill.INSERT)
    private Instant createTime;

    /** 最后更新时间，按 UTC 保存。 */
    @TableField(value = "update_time", fill = FieldFill.INSERT_UPDATE)
    private Instant updateTime;
}
