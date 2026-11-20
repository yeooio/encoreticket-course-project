package io.github.chenforegone.encoreticket.controller.user;

import io.github.chenforegone.encoreticket.common.error.ErrorCode;
import io.github.chenforegone.encoreticket.common.exception.InvalidRequestException;
import io.github.chenforegone.encoreticket.common.response.*;
import io.github.chenforegone.encoreticket.model.dto.*;
import io.github.chenforegone.encoreticket.model.vo.*;
import io.github.chenforegone.encoreticket.service.OrderService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import java.util.*;

/**
 * 本人订单四入口；保留既有支付退款入口，不新增用户目录别名。
 * @author c1342
 */
@RestController
@RequestMapping("/orders")
public class UserOrderWorkflowController {
    private final OrderService orders;
    public UserOrderWorkflowController(OrderService orders) { this.orders=orders; }
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<OrderDetailVO> create(@RequestHeader("Idempotency-Key") String key,
            @RequestBody CreateOrderRequest body,Authentication authentication,HttpServletRequest request) {
        query(request,Set.of());
        if(Collections.list(request.getHeaders("Idempotency-Key")).size()!=1) throw invalid();
        return ApiResponse.of(orders.create(user(authentication),key,body));
    }
    @GetMapping
    public ApiResponse<PageResponse<OrderSummaryVO>> list(@RequestParam(required=false) Integer page,
            @RequestParam(required=false) Integer pageSize,@RequestParam(required=false) String status,
            @RequestParam(required=false) String screeningId,@RequestParam(required=false) String orderNo,
            @RequestBody(required=false) byte[] body,Authentication authentication,HttpServletRequest request) {
        empty(body); query(request,Set.of("page","pageSize","status","screeningId","orderNo"));
        return ApiResponse.of(orders.list(user(authentication),new OrderQueryRequest(page,pageSize,status,screeningId,orderNo)));
    }
    @GetMapping("/{orderId}")
    public ApiResponse<OrderDetailVO> detail(@PathVariable String orderId,@RequestBody(required=false) byte[] body,
            Authentication authentication,HttpServletRequest request) {
        empty(body); query(request,Set.of());
        return ApiResponse.of(orders.detail(user(authentication),orderId));
    }
    @PostMapping("/{orderId}/cancel")
    public ApiResponse<OrderCancelledVO> cancel(@PathVariable String orderId,@RequestBody(required=false) byte[] body,
            Authentication authentication,HttpServletRequest request) {
        empty(body); query(request,Set.of());
        return ApiResponse.of(orders.cancel(user(authentication),orderId));
    }
    private static Long user(Authentication authentication) {
        if(authentication==null||!authentication.isAuthenticated())
            throw new org.springframework.security.authentication.AuthenticationCredentialsNotFoundException("未登录");
        return Long.valueOf(authentication.getName());
    }
    private static void query(HttpServletRequest request,Set<String> allowed) {
        if(request.getParameterMap().entrySet().stream().anyMatch(e->!allowed.contains(e.getKey())||e.getValue().length!=1)) throw invalid();
    }
    private static void empty(byte[] body) { if(body!=null&&body.length>0) throw invalid(); }
    private static InvalidRequestException invalid() { return new InvalidRequestException(ErrorCode.INVALID_ARGUMENT); }
}
