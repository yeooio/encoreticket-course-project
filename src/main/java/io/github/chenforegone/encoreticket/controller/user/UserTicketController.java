package io.github.chenforegone.encoreticket.controller.user;

import io.github.chenforegone.encoreticket.common.response.ApiResponse;
import io.github.chenforegone.encoreticket.common.response.PageResponse;
import io.github.chenforegone.encoreticket.model.dto.TicketQueryRequest;
import io.github.chenforegone.encoreticket.model.vo.TicketDetailVO;
import io.github.chenforegone.encoreticket.model.vo.TicketStatusVO;
import io.github.chenforegone.encoreticket.model.vo.TicketSummaryVO;
import io.github.chenforegone.encoreticket.service.TicketService;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 当前登录用户的票券查询入口。
 *
 * @author c1342
 */
@RestController
@RequestMapping("/tickets")
public class UserTicketController {
    private final TicketService service;

    public UserTicketController(TicketService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<PageResponse<TicketSummaryVO>> list(
            @AuthenticationPrincipal String userId, @Valid @ModelAttribute TicketQueryRequest request) {
        return ApiResponse.of(service.list(userId, request));
    }

    @GetMapping("/{ticketId}")
    public ApiResponse<TicketDetailVO> getDetail(
            @AuthenticationPrincipal String userId, @PathVariable String ticketId) {
        return ApiResponse.of(service.getDetail(userId, ticketId));
    }

    @GetMapping("/{ticketId}/status")
    public ApiResponse<TicketStatusVO> getStatus(
            @AuthenticationPrincipal String userId, @PathVariable String ticketId) {
        return ApiResponse.of(service.getStatus(userId, ticketId));
    }
}
