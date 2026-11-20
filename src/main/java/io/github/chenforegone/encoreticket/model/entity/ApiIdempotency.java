package io.github.chenforegone.encoreticket.model.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * 支付和核销共用的成功幂等快照，按操作人、操作名及请求键定位。
 *
 * @author c1342
 */
@Getter
@Setter
@TableName("api_idempotency")
public class ApiIdempotency {
    private Long operatorId;
    private String operation;
    private String idempotencyKey;
    private String requestDigest;
    private String responseJson;
    private Instant expiresAt;
}
