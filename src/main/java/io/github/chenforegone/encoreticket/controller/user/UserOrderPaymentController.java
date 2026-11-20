package io.github.chenforegone.encoreticket.controller.user;

import io.github.chenforegone.encoreticket.common.response.ApiResponse;
import io.github.chenforegone.encoreticket.model.vo.OrderPaymentsVO;
import io.github.chenforegone.encoreticket.model.vo.PaymentVO;
import io.github.chenforegone.encoreticket.service.PaymentService;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 用户订单支付入口。
 *
 * @author c1342
 */
@RestController
@RequestMapping("/orders/{orderId}/payments")
@Validated
public class UserOrderPaymentController {

    private final PaymentService paymentService;

    public UserOrderPaymentController(PaymentService paymentService) {
        this.paymentService = paymentService;
    }

    @PostMapping
    public ApiResponse<PaymentVO> create(
            @PathVariable @Positive Long orderId,
            @RequestHeader("Idempotency-Key") @NotBlank String idempotencyKey,
            Authentication authentication
    ) {
        return ApiResponse.of(paymentService.createOrContinue(orderId, currentUserId(authentication), idempotencyKey));
    }

    @GetMapping
    public ApiResponse<OrderPaymentsVO> list(
            @PathVariable @Positive Long orderId,
            Authentication authentication
    ) {
        return ApiResponse.of(paymentService.listByOrder(orderId, currentUserId(authentication)));
    }

    private static Long currentUserId(Authentication authentication) {
        return Long.valueOf(authentication.getName());
    }
}
