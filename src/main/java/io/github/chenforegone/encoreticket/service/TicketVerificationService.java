package io.github.chenforegone.encoreticket.service;

import io.github.chenforegone.encoreticket.common.response.PageResponse;
import io.github.chenforegone.encoreticket.model.dto.TicketVerificationRequest;
import io.github.chenforegone.encoreticket.model.dto.TicketVerificationQueryRequest;
import io.github.chenforegone.encoreticket.model.vo.TicketVerificationVO;
import io.github.chenforegone.encoreticket.model.vo.TicketVerificationAdminVO;

/**
 * 核销与只读审计查询。
 *
 * @author c1342
 */
public interface TicketVerificationService {
    TicketVerificationVO verify(long operator, String key, TicketVerificationRequest request);
    PageResponse<TicketVerificationAdminVO> list(TicketVerificationQueryRequest request);
}
