package io.github.chenforegone.encoreticket.controller.admin;

import io.github.chenforegone.encoreticket.common.error.ErrorCode;
import io.github.chenforegone.encoreticket.common.exception.InvalidRequestException;
import io.github.chenforegone.encoreticket.common.response.*;
import io.github.chenforegone.encoreticket.model.vo.*;
import io.github.chenforegone.encoreticket.service.RefundService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import java.time.Instant;
import java.util.Map;
import java.util.Set;

/**
 * 管理员仅可核查未知结果或重试明确失败，不能设置资金结果。
 * @author c1342
 */
@RestController
@RequestMapping("/admin/refunds")
public class AdminRefundController {
    private final RefundService refunds;
    public AdminRefundController(RefundService refunds) { this.refunds=refunds; }
    @GetMapping public ApiResponse<PageResponse<RefundVO>> page(
            @RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int pageSize,
            @RequestParam(required=false) String status,@RequestParam(required=false) String source,
            @RequestParam(required=false) Long orderId,@RequestParam(required=false) Instant createdFrom,
            @RequestParam(required=false) Instant createdTo,@RequestBody(required=false) byte[] body,HttpServletRequest request) {
        if(body!=null&&body.length>0) throw invalid();
        for(var entry:request.getParameterMap().entrySet()) if(!Set.of("page","pageSize","status","source","orderId","createdFrom","createdTo").contains(entry.getKey())||entry.getValue().length!=1) throw invalid();
        return ApiResponse.of(refunds.adminPage(page,pageSize,status,source,orderId,createdFrom,createdTo));
    }
    @GetMapping("/{refundId}") public ApiResponse<RefundDetailVO> detail(@PathVariable Long refundId,
            @RequestBody(required=false) byte[] body,HttpServletRequest request) {
        if((body!=null&&body.length>0)||!request.getParameterMap().isEmpty()) throw invalid();
        return ApiResponse.of(refunds.adminDetail(refundId));
    }
    @PostMapping("/{refundId}/process") public ApiResponse<RefundVO> process(@PathVariable Long refundId,
            @RequestHeader("Idempotency-Key") String key,@RequestBody Map<String,Object> body,
            Authentication auth,HttpServletRequest request) {
        if(body.size()!=1||!(body.get("action") instanceof String action)||!Set.of("RETRY","CHECK").contains(action)
                ||!request.getParameterMap().isEmpty()||java.util.Collections.list(request.getHeaders("Idempotency-Key")).size()!=1) throw invalid();
        return ApiResponse.of(refunds.adminProcess(refundId,Long.valueOf(auth.getName()),key,action));
    }
    private static InvalidRequestException invalid() { return new InvalidRequestException(ErrorCode.INVALID_ARGUMENT); }
}
