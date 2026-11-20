package io.github.chenforegone.encoreticket.model.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.github.chenforegone.encoreticket.model.enums.SeatStatus;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * 影厅物理座位实体，对应 {@code seat} 表。
 *
 * @author c1342
 */
@Getter
@Setter
@TableName("seat")
public class Seat {

    /** 物理座位主键。 */
    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private Long id;

    /** 影厅 ID，逻辑关联 {@code hall.id}。 */
    private Long hallId;

    /** 座位排号，从 1 开始。 */
    @TableField("`row_number`")
    private Integer rowNumber;

    /** 排内座位号，从 1 开始。 */
    private Integer seatNumber;

    /** 物理座位状态，数据库保存 TINYINT。 */
    private SeatStatus status;

    /** 物理座位独立编辑版本，不是场次座位销售版本。 */
    private Integer version;

    /** 创建时间，按 UTC 保存。 */
    @TableField(value = "create_time", fill = FieldFill.INSERT)
    private Instant createTime;

    /** 最后更新时间，按 UTC 保存。 */
    @TableField(value = "update_time", fill = FieldFill.INSERT_UPDATE)
    private Instant updateTime;
}
