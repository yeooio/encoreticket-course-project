package io.github.chenforegone.encoreticket.model.vo;

import java.time.Instant;
import java.util.List;

/**
 * 管理端安全退款及处理历史。
 * @author c1342
 */
public record RefundDetailVO(RefundVO refund,List<Attempt> attempts,List<Audit> audits) {
    /**
     * 渠道处理轮次摘要。
     * @author c1342
     */
    public record Attempt(int attemptNo,String status,Instant createdAt,Instant updatedAt) { }
    /**
     * 不可变操作摘要。
     * @author c1342
     */
    public record Audit(String operation,String operatorId,Instant createdAt) { }
}
