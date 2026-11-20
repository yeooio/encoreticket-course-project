package io.github.chenforegone.encoreticket.simulation.payment;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 测试脚本显式指定的事件目标，不接受客户端金额或渠道。
 * @author c1342
 */
public record PaymentSimulationEventRequest(
        @NotBlank @Size(max = 64) String paymentNo,
        @NotBlank @Size(max = 64) String attemptId,
        @NotBlank @jakarta.validation.constraints.Pattern(regexp = "[\\x20-\\x7E]{1,128}") String eventId,
        @NotNull Result result) {
    /**
     * 模拟渠道的明确结果。
     * @author c1342
     */
    public enum Result { SUCCESS, FAILED }
}
