package io.github.chenforegone.encoreticket.model.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.github.chenforegone.encoreticket.model.enums.PaymentStatus;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 支付记录实体，对应 {@code payment} 表。
 *
 * @author c1342
 */
@Getter
@Setter
@TableName("payment")
public class Payment {

    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private Long id;

    private String paymentNo;
    private Long orderId;
    private BigDecimal amount;
    private String currency;
    private PaymentStatus status;
    private Integer version;

    private String attemptId;

    @TableField(value = "create_time", fill = FieldFill.INSERT)
    private Instant createTime;

    @TableField(value = "update_time", fill = FieldFill.INSERT_UPDATE)
    private Instant updateTime;
}
