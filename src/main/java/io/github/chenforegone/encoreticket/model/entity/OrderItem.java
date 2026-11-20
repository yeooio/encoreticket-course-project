package io.github.chenforegone.encoreticket.model.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 订单明细实体，对应 {@code order_item} 表。
 *
 * @author c1342
 */
@Getter
@Setter
@TableName("order_item")
public class OrderItem {

    /** 订单明细主键。 */
    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private Long id;

    /** 订单 ID，逻辑关联 {@code orders.id}。 */
    private Long orderId;

    /** 场次座位 ID，逻辑关联 {@code screening_seat.id}。 */
    private Long screeningSeatId;

    /** 购票时的单价快照，非负，精确到小数点后两位。 */
    private BigDecimal unitPrice;

    /** 创建时间，按 UTC 保存。 */
    @TableField(value = "create_time", fill = FieldFill.INSERT)
    private Instant createTime;

    /** 最后更新时间，按 UTC 保存。 */
    @TableField(value = "update_time", fill = FieldFill.INSERT_UPDATE)
    private Instant updateTime;
}
