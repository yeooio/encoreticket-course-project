package io.github.chenforegone.encoreticket.model.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.github.chenforegone.encoreticket.model.enums.TicketStatus;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * 电子票券实体，对应 {@code ticket} 表。
 *
 * <p>票券码是扫码入场凭证，不得输出到日志。
 *
 * @author c1342
 */
@Getter
@Setter
@TableName("ticket")
public class Ticket {

    /** 票券主键。 */
    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private Long id;

    /** 票券状态，数据库保存 TINYINT。 */
    private TicketStatus status;

    /** 订单明细 ID，逻辑关联 {@code order_item.id}，每个明细最多生成一张票券。 */
    private Long orderItemId;

    /** 供用户扫码入场的票券码，全局唯一。 */
    private String ticketCode;

    /** 创建时间，按 UTC 保存。 */
    @TableField(value = "create_time", fill = FieldFill.INSERT)
    private Instant createTime;

    /** 最后更新时间，按 UTC 保存。 */
    @TableField(value = "update_time", fill = FieldFill.INSERT_UPDATE)
    private Instant updateTime;
}
