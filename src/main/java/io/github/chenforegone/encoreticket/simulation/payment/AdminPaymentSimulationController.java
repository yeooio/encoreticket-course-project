package io.github.chenforegone.encoreticket.simulation.payment;

import io.github.chenforegone.encoreticket.common.response.ApiResponse;
import io.github.chenforegone.encoreticket.model.vo.PaymentVO;
import io.github.chenforegone.encoreticket.service.PaymentChannelResultHandler;
import jakarta.validation.Valid;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * 受环境开关和管理员权限双重隔离的同步模拟入口。
 * @author c1342
 */
@RestController
@RequestMapping("/admin/test/payments")
@Profile("(local | test | loadtest) & !prod")
@ConditionalOnProperty(name = "encore.payment.simulation.enabled", havingValue = "true")
@PreAuthorize("hasRole('ADMIN')")
public class AdminPaymentSimulationController {
    private final PaymentSimulationService queries;
    private final PaymentChannelResultHandler handler;

    public AdminPaymentSimulationController(PaymentSimulationService queries, PaymentChannelResultHandler handler) {
        this.queries = queries;
        this.handler = handler;
    }

    @GetMapping("/{paymentNo}/attempts")
    public ApiResponse<PaymentSimulationService.AttemptPage> attempts(@PathVariable String paymentNo,
            @RequestParam(defaultValue = "20") int limit, @RequestParam(required = false) String cursor) {
        return ApiResponse.of(queries.attempts(paymentNo, limit, cursor));
    }

    @PostMapping("/events")
    public ApiResponse<PaymentVO> event(@Valid @RequestBody PaymentSimulationEventRequest request) {
        // 不开启外层事务，等待注入的结果处理事务代理完成提交后再返回。
        return ApiResponse.of(handler.handleChannelEvent(queries.event(request)));
    }
}
