package io.github.chenforegone.encoreticket.controller.user;

import io.github.chenforegone.encoreticket.common.error.ErrorCode;
import io.github.chenforegone.encoreticket.common.exception.InvalidRequestException;
import io.github.chenforegone.encoreticket.common.response.ApiResponse;
import io.github.chenforegone.encoreticket.model.vo.*;
import io.github.chenforegone.encoreticket.service.RefundService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

/**
 * 订单关联退款入口，金额及身份均由服务端确定。
 * @author c1342
 */
@RestController
@RequestMapping("/orders")
public class UserOrderController {
    private final RefundService refunds;
    public UserOrderController(RefundService refunds) { this.refunds=refunds; }

    @PostMapping("/{orderId}/refunds")
    public ApiResponse<RefundVO> applyRefund(@PathVariable Long orderId,
            @RequestHeader("Idempotency-Key") String key,@RequestBody(required=false) byte[] body,
            Authentication authentication,HttpServletRequest request) {
        emptyRequest(body,request);
        if(java.util.Collections.list(request.getHeaders("Idempotency-Key")).size()!=1) throw invalid();
        return ApiResponse.of(refunds.apply(orderId,Long.valueOf(authentication.getName()),key));
    }
    @GetMapping("/{orderId}/refunds")
    public ApiResponse<OrderRefundsVO> queryRefunds(@PathVariable Long orderId,
            @RequestBody(required=false) byte[] body,Authentication authentication,HttpServletRequest request) {
        emptyRequest(body,request);
        return ApiResponse.of(refunds.byOrder(orderId,Long.valueOf(authentication.getName())));
    }
    private static void emptyRequest(byte[] body,HttpServletRequest request) {
        if((body!=null&&body.length>0)||!request.getParameterMap().isEmpty()) throw invalid();
    }
    private static InvalidRequestException invalid() { return new InvalidRequestException(ErrorCode.INVALID_ARGUMENT); }
    // 创建、列表、详情和取消入口由 UserOrderWorkflowController 提供。
}
