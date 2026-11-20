package io.github.chenforegone.encoreticket.model.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

/**
 * 可信支付渠道结果模型，仅供内部模拟渠道使用。
 *
 * @author c1342
 */
public record PaymentResultRequest(
        @NotBlank String provider,
        @NotBlank String eventId,
        @NotBlank String eventType,
        @NotNull BigDecimal amount,
        @NotNull Boolean successful
) {
}
