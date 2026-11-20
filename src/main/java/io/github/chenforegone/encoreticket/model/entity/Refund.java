package io.github.chenforegone.encoreticket.model.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.FieldFill;
import io.github.chenforegone.encoreticket.model.enums.RefundStatus;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 退款记录实体，对应 {@code refund} 表。
 *
 * @author c1342
 */
@Getter
@Setter
@TableName("refund")
public class Refund {
    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private Long id;
    private String refundNo;
    private Long orderId;
    private Long paymentId;
    private BigDecimal amount;
    private RefundStatus status;
    private String source;
    private Integer attemptNo;
    private Instant nextRunAt;
    private String leaseToken;
    private Instant leaseUntil;
    @TableField(fill = FieldFill.INSERT)
    private Instant createTime;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private Instant updateTime;
}
