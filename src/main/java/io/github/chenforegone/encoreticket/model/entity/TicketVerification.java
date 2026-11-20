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
 * 票券成功核销记录实体，对应 {@code ticket_verification} 表。
 *
 * <p>仅记录成功核销，写入后作为不可变审计记录保留，没有状态和更新时间字段。
 *
 * @author c1342
 */
@Getter
@Setter
@TableName("ticket_verification")
public class TicketVerification {

    /** 核销记录主键。 */
    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private Long id;

    /** 票券 ID，逻辑关联 {@code ticket.id}，每张票券最多有一条成功核销记录。 */
    private Long ticketId;

    /** 核销人员 ID，逻辑关联 {@code sys_user.id}。 */
    private Long operatorId;

    /** 扫码设备编号，不关联独立设备表，去除首尾空格后不得为空。 */
    private String deviceCode;

    /** 实际核销时间，按 UTC 保存，由业务层在核销成功时设置。 */
    private Instant verifiedAt;

    /** 核销记录创建时间，按 UTC 保存。 */
    @TableField(value = "create_time", fill = FieldFill.INSERT)
    private Instant createTime;
}
