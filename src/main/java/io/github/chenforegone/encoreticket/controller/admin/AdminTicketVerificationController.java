package io.github.chenforegone.encoreticket.controller.admin;

import io.github.chenforegone.encoreticket.common.error.ErrorCode;
import io.github.chenforegone.encoreticket.common.response.ApiResponse;
import io.github.chenforegone.encoreticket.common.response.PageResponse;
import io.github.chenforegone.encoreticket.model.dto.TicketVerificationRequest;
import io.github.chenforegone.encoreticket.model.dto.TicketVerificationQueryRequest;
import io.github.chenforegone.encoreticket.model.vo.TicketVerificationVO;
import io.github.chenforegone.encoreticket.model.vo.TicketVerificationAdminVO;
import io.github.chenforegone.encoreticket.common.exception.TicketVerificationException;
import io.github.chenforegone.encoreticket.service.TicketVerificationService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import java.util.Set;

/**
 * 扫码核销及管理员只读审计入口。
 *
 * @author c1342
 */
@RestController
public class AdminTicketVerificationController {
    private final TicketVerificationService service;
    public AdminTicketVerificationController(TicketVerificationService service) { this.service=service; }

    @PostMapping("/ticket-verifications")
    @PreAuthorize("hasRole('CHECKIN_STAFF')")
    public ApiResponse<TicketVerificationVO> verify(@RequestHeader("Idempotency-Key") String key,
            @RequestBody TicketVerificationRequest body,Authentication authentication,HttpServletRequest request) {
        body.validatedScreeningId();
        if (!key.matches("[A-Za-z0-9_-]{1,128}") || !request.getParameterMap().isEmpty()
                || java.util.Collections.list(request.getHeaders("Idempotency-Key")).size()!=1) {
            throw new TicketVerificationException(ErrorCode.INVALID_ARGUMENT);
        }
        Long operator = Long.valueOf(authentication.getName());
        return ApiResponse.of(service.verify(operator,key,body));
    }

    @GetMapping("/admin/ticket-verifications")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<PageResponse<TicketVerificationAdminVO>> list(@ModelAttribute TicketVerificationQueryRequest query,
            HttpServletRequest request) {
        Set<String> allowed=Set.of("page","pageSize","screeningId","operatorId","ticketCode","deviceCode","verifiedAtFrom","verifiedAtTo");
        if (request.getContentLengthLong()>0 || request.getParameterMap().entrySet().stream()
                .anyMatch(e -> !allowed.contains(e.getKey()) || e.getValue().length!=1)) {
            throw new TicketVerificationException(ErrorCode.INVALID_ARGUMENT);
        }
        query.validate();
        return ApiResponse.of(service.list(query));
    }
}
