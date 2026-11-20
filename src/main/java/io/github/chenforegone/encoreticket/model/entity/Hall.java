package io.github.chenforegone.encoreticket.model.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.github.chenforegone.encoreticket.model.enums.HallStatus;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * 影厅实体，对应 {@code hall} 表。
 *
 * @author c1342
 */
@Getter
@Setter
@TableName("hall")
public class Hall {

    /** 影厅主键。 */
    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private Long id;

    /** 影厅名称。 */
    private String hallName;

    /** 影厅编号，全局唯一。 */
    private String hallNo;

    /** 影厅状态，数据库保存 TINYINT。 */
    private HallStatus status;

    private Integer version;

    /** 同影厅排片操作共同竞争的版本；由排片条件更新显式维护。 */
    private Long scheduleVersion;

    /** 创建时间，按 UTC 保存。 */
    @TableField(value = "create_time", fill = FieldFill.INSERT)
    private Instant createTime;

    /** 最后更新时间，按 UTC 保存。 */
    @TableField(value = "update_time", fill = FieldFill.INSERT_UPDATE)
    private Instant updateTime;
}
