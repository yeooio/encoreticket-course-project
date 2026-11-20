package io.github.chenforegone.encoreticket.model.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * 支付渠道通知去重记录。
 *
 * @author c1342
 */
@Getter
@Setter
@TableName("payment_notification")
public class PaymentNotification {
    private Long id;
    private String provider;
    private String eventId;
    private String paymentNo;
    private String eventType;
    private byte[] payloadHash;
    private Instant processedAt;
    private Instant createTime;
    private String attemptId;
}
