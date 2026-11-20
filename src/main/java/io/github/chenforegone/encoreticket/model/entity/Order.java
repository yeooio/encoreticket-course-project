package io.github.chenforegone.encoreticket.model.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.FieldFill;
import io.github.chenforegone.encoreticket.model.enums.OrderStatus;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 订单实体，对应 {@code orders} 表。
 *
 * @author c1342
 */
@Getter
@Setter
@TableName("orders")
public class Order {
    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private Long id;
    @TableField(fill = FieldFill.INSERT)
    private Instant createTime;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private Instant updateTime;
    private String orderNo;
    private Long userId;
    private Long screeningId;
    private BigDecimal totalAmount;
    private Instant expiresAt;
    private OrderStatus status;
    private Integer version;
}
