package io.github.chenforegone.encoreticket.service;

import io.github.chenforegone.encoreticket.common.response.PageResponse;
import io.github.chenforegone.encoreticket.model.dto.TicketQueryRequest;
import io.github.chenforegone.encoreticket.model.vo.TicketDetailVO;
import io.github.chenforegone.encoreticket.model.vo.TicketStatusVO;
import io.github.chenforegone.encoreticket.model.vo.TicketSummaryVO;

/**
 * 当前登录用户的只读票券查询。
 *
 * @author c1342
 */
public interface TicketService {
    PageResponse<TicketSummaryVO> list(String userId, TicketQueryRequest request);

    TicketDetailVO getDetail(String userId, String ticketId);

    TicketStatusVO getStatus(String userId, String ticketId);
}
