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
 * 角色实体，对应 {@code role} 表。
 *
 * <p>角色编码是鉴权使用的稳定标识，角色主键由 {@code V2__seed_roles.sql} 固定写入。
 *
 * @author c1342
 */
@Getter
@Setter
@TableName("role")
public class Role {

    /** 角色主键。 */
    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private Long id;

    /** 角色编码，例如 ADMIN、CHECKIN_STAFF、USER。 */
    private String roleCode;

    /** 角色名称。 */
    private String roleName;

    /** 角色描述。 */
    private String roleDesc;

    /** 创建时间，按 UTC 保存。 */
    @TableField(value = "create_time", fill = FieldFill.INSERT)
    private Instant createTime;

    /** 最后更新时间，按 UTC 保存。 */
    @TableField(value = "update_time", fill = FieldFill.INSERT_UPDATE)
    private Instant updateTime;
}
