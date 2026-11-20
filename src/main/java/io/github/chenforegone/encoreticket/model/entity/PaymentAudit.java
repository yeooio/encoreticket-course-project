package io.github.chenforegone.encoreticket.model.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * 支付审计记录。
 *
 * @author c1342
 */
@Getter
@Setter
@TableName("payment_audit")
public class PaymentAudit {
    private Long id;
    private Long paymentId;
    private Long orderId;
    private Long operatorId;
    private String operation;
    private Integer fromStatus;
    private Integer toStatus;
    private String source;
    private String requestId;
    private String eventId;
    private String remark;
    private Instant createTime;
}
