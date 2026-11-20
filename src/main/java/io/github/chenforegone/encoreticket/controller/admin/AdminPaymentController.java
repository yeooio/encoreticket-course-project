package io.github.chenforegone.encoreticket.controller.admin;

import io.github.chenforegone.encoreticket.common.response.ApiResponse;
import io.github.chenforegone.encoreticket.common.response.PageResponse;
import io.github.chenforegone.encoreticket.model.dto.AdminPaymentQueryRequest;
import io.github.chenforegone.encoreticket.model.vo.PaymentDetailVO;
import io.github.chenforegone.encoreticket.model.vo.PaymentSummaryVO;
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
 * 管理员支付异常处理入口。
 *
 * @author c1342
 */
@RestController
@RequestMapping("/admin/payments")
@Validated
public class AdminPaymentController {

    private final PaymentService paymentService;

    public AdminPaymentController(PaymentService paymentService) {
        this.paymentService = paymentService;
    }

    @GetMapping
    public ApiResponse<PageResponse<PaymentSummaryVO>> page(@jakarta.validation.Valid AdminPaymentQueryRequest request) {
        return ApiResponse.of(paymentService.adminPage(request));
    }

    @GetMapping("/{paymentId}")
    public ApiResponse<PaymentDetailVO> detail(@PathVariable @Positive Long paymentId) {
        return ApiResponse.of(paymentService.adminDetail(paymentId));
    }

    @PostMapping("/{paymentId}/retry")
    public ApiResponse<PaymentVO> retry(
            @PathVariable @Positive Long paymentId,
            @RequestHeader("Idempotency-Key") @NotBlank String idempotencyKey,
            Authentication authentication
    ) {
        return ApiResponse.of(paymentService.retryFailed(
                paymentId,
                Long.valueOf(authentication.getName()),
                idempotencyKey));
    }
}
