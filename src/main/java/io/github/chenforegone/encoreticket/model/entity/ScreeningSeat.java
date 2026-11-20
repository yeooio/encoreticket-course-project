package io.github.chenforegone.encoreticket.model.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.github.chenforegone.encoreticket.model.enums.ScreeningSeatStatus;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * 场次座位销售状态实体，对应 {@code screening_seat} 表。
 *
 * @author c1342
 */
@Getter
@Setter
@TableName("screening_seat")
public class ScreeningSeat {

    /** 场次座位主键。 */
    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private Long id;

    /** 物理座位 ID，逻辑关联 {@code seat.id}。 */
    private Long seatId;

    /** 场次 ID，逻辑关联 {@code screening.id}。 */
    private Long screeningId;

    /** 场次座位销售状态，数据库保存 TINYINT。 */
    private ScreeningSeatStatus status;

    /**
     * 占用或售出该座位的订单 ID，逻辑关联 {@code orders.id}，未关联订单时为 null。
     *
     * <p>沿用默认的非空字段更新策略；释放座位时，业务层需通过条件更新显式
     * {@code SET order_id = NULL}，不能仅将本字段设为 null 后调用实体更新。
     */
    private Long orderId;

    /** 创建时间，按 UTC 保存。 */
    @TableField(value = "create_time", fill = FieldFill.INSERT)
    private Instant createTime;

    /** 最后更新时间，按 UTC 保存。 */
    @TableField(value = "update_time", fill = FieldFill.INSERT_UPDATE)
    private Instant updateTime;
}
