package io.github.chenforegone.encoreticket.service;

import io.github.chenforegone.encoreticket.common.response.PageResponse;
import io.github.chenforegone.encoreticket.model.dto.AdminPaymentQueryRequest;
import io.github.chenforegone.encoreticket.model.vo.OrderPaymentsVO;
import io.github.chenforegone.encoreticket.model.vo.PaymentDetailVO;
import io.github.chenforegone.encoreticket.model.vo.PaymentSummaryVO;
import io.github.chenforegone.encoreticket.model.vo.PaymentVO;

/**
 * 支付业务服务。
 *
 * @author c1342
 */
public interface PaymentService {

    PaymentVO createOrContinue(Long orderId, Long userId, String idempotencyKey);

    OrderPaymentsVO listByOrder(Long orderId, Long userId);

    PaymentVO retryFailed(Long paymentId, Long operatorId, String idempotencyKey);

    PageResponse<PaymentSummaryVO> adminPage(AdminPaymentQueryRequest request);

    PaymentDetailVO adminDetail(Long paymentId);

}
