package io.github.chenforegone.encoreticket.service.impl;

import io.github.chenforegone.encoreticket.common.time.Clock;

import io.github.chenforegone.encoreticket.repository.PaymentSettlementRepository;
import io.github.chenforegone.encoreticket.gateway.PaymentChannelEvent;
import io.github.chenforegone.encoreticket.gateway.PaymentGateway;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import io.github.chenforegone.encoreticket.common.error.ErrorCode;
import io.github.chenforegone.encoreticket.common.exception.*;
import io.github.chenforegone.encoreticket.common.response.PageResponse;
import io.github.chenforegone.encoreticket.mapper.*;
import io.github.chenforegone.encoreticket.model.dto.AdminPaymentQueryRequest;
import io.github.chenforegone.encoreticket.model.entity.*;
import io.github.chenforegone.encoreticket.model.enums.*;
import io.github.chenforegone.encoreticket.model.vo.*;
import io.github.chenforegone.encoreticket.service.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * 支付事务编排：订单锁、请求快照、当前渠道尝试、出票及迟到收款处理。
 * @author c1342
 */
@Service
public class PaymentServiceImpl implements PaymentService, PaymentChannelResultHandler {
    public static final String CREATE_OPERATION = "payment-create:v1";
    public static final String RETRY_OPERATION = "payment-admin-retry:v1";
    private final PaymentMapper paymentMapper;
    private final OrderMapper orderMapper;
    private final PaymentAuditMapper auditMapper;
    private final RefundMapper refundMapper;
    private final PaymentIdempotencyMapper idempotencyMapper;
    private final PaymentSettlementRepository settlement;
    private final PaymentGateway gateway;
    private final Clock clock;
    private final JsonMapper json;

    public PaymentServiceImpl(PaymentMapper paymentMapper, OrderMapper orderMapper,
            PaymentAuditMapper auditMapper, RefundMapper refundMapper,
            PaymentIdempotencyMapper idempotencyMapper, PaymentSettlementRepository settlement,
            PaymentGateway gateway, Clock clock, JsonMapper json) {
        this.paymentMapper = paymentMapper;
        this.orderMapper = orderMapper;
        this.auditMapper = auditMapper;
        this.refundMapper = refundMapper;
        this.idempotencyMapper = idempotencyMapper;
        this.settlement = settlement;
        this.gateway = gateway;
        this.clock = clock;
        this.json = json;
    }

    @Override
    @Transactional(isolation = org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public PaymentVO createOrContinue(Long orderId, Long userId, String key) {
        requireKey(key);
        requireId(userId);
        Order order = lockOrder(orderId);
        requireOwner(order, userId);
        PaymentVO replay = replay(userId, CREATE_OPERATION, key, orderId);
        if (replay != null) return replay;
        List<Payment> payments = paymentMapper.lockByOrder(orderId);
        Payment successful = payments.stream().filter(p -> p.getStatus() == PaymentStatus.SUCCESS).findFirst().orElse(null);
        if (successful != null) {
            amount(order, successful);
            if (order.getStatus() != OrderStatus.PAID) throw new ConflictException(ErrorCode.PAYMENT_ORDER_NOT_PAYABLE);
            return save(userId, CREATE_OPERATION, key, successful);
        }
        ensurePayable(order);
        Payment current = payments.isEmpty() ? null : payments.getFirst();
        if (current != null) {
            amount(order, current);
            if (current.getStatus() == PaymentStatus.PENDING) return save(userId, CREATE_OPERATION, key, current);
            if (current.getStatus() != PaymentStatus.FAILED) throw conflict();
            transition(current, PaymentStatus.PENDING);
            audit(current, userId, "USER_RETRY", PaymentStatus.FAILED, "USER", null);
        } else {
            current = newPayment(order);
        }
        startAttempt(current, userId, "USER", "CREATE_OR_CONTINUE");
        return save(userId, CREATE_OPERATION, key, current);
    }

    @Override
    @Transactional(isolation = org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public PaymentVO retryFailed(Long paymentId, Long operatorId, String key) {
        requireKey(key);
        requireId(operatorId);
        Payment hint = requirePayment(paymentId);
        Order order = lockOrder(hint.getOrderId());
        PaymentVO replay = replay(operatorId, RETRY_OPERATION, key, paymentId);
        if (replay != null) return replay;
        List<Payment> payments = paymentMapper.lockByOrder(order.getId());
        Payment failed = payments.stream().filter(p -> p.getId().equals(paymentId)).findFirst().orElseThrow(PaymentServiceImpl::conflict);
        if (payments.isEmpty() || !payments.getFirst().getId().equals(paymentId)
                || failed.getStatus() != PaymentStatus.FAILED
                || payments.stream().anyMatch(p -> p.getStatus() != PaymentStatus.FAILED)) {
            throw new ConflictException(ErrorCode.PAYMENT_RETRY_NOT_ALLOWED);
        }
        amount(order, failed);
        ensurePayable(order);
        Payment retry = newPayment(order);
        startAttempt(retry, operatorId, "ADMIN", "ADMIN_RETRY");
        audit(failed, operatorId, "ADMIN_RETRY_SOURCE", PaymentStatus.FAILED, "ADMIN", null);
        return save(operatorId, RETRY_OPERATION, key, retry);
    }

    @Override
    @Transactional(readOnly = true)
    public OrderPaymentsVO listByOrder(Long orderId, Long userId) {
        requireId(orderId);
        Order order = orderMapper.selectById(orderId);
        if (order == null) throw new ResourceNotFoundException(ErrorCode.RESOURCE_NOT_FOUND);
        requireOwner(order, userId);
        List<PaymentVO> items = paymentMapper.selectList(new QueryWrapper<Payment>().eq("order_id", orderId)
                .orderByDesc("create_time", "id")).stream().map(PaymentVO::from).toList();
        return new OrderPaymentsVO(String.valueOf(order.getId()), OrderPaymentsVO.statusValue(order.getStatus()), items);
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<PaymentSummaryVO> adminPage(AdminPaymentQueryRequest request) {
        AdminPaymentQueryRequest query = request == null
                ? new AdminPaymentQueryRequest(null, null, null, null, null, null, null) : request;
        if (query.createdFrom() != null && query.createdTo() != null && query.createdFrom().isAfter(query.createdTo())) {
            throw new InvalidRequestException(ErrorCode.INVALID_ARGUMENT);
        }
        int page = query.actualPage(), size = query.actualPageSize();
        if (page < 1 || size < 1 || size > 100 || (long) (page - 1) * size > Integer.MAX_VALUE) {
            throw new InvalidRequestException(ErrorCode.INVALID_ARGUMENT);
        }
        return new PageResponse<>(paymentMapper.selectAdminPage(query, (page - 1) * size, size).stream()
                .map(PaymentVO::from).map(PaymentSummaryVO::from).toList(), page, size, paymentMapper.countAdmin(query));
    }

    @Override
    @Transactional(readOnly = true)
    public PaymentDetailVO adminDetail(Long paymentId) {
        Payment payment = requirePayment(paymentId);
        List<PaymentAuditVO> audits = auditMapper.selectList(new QueryWrapper<PaymentAudit>()
                .eq("payment_id", paymentId).orderByAsc("create_time", "id")).stream()
                .map(a -> new PaymentAuditVO(a.getOperation(), statusValue(a.getFromStatus()), statusValue(a.getToStatus()),
                        a.getSource(), a.getRequestId(), a.getEventId(), a.getRemark(), a.getCreateTime())).toList();
        Order order = orderMapper.selectById(payment.getOrderId());
        if (order == null) throw new IllegalStateException("支付关联订单不存在");
        Refund refund = refundMapper.selectOne(new QueryWrapper<Refund>().eq("payment_id", paymentId));
        return new PaymentDetailVO(PaymentVO.from(payment), PaymentDetailVO.OrderSummary.from(order),
                refund == null ? null : PaymentDetailVO.RefundSummary.from(refund, payment.getCurrency()),
                audits, paymentMapper.selectNotificationSummaries(payment.getPaymentNo()));
    }

    /**
     * 只接受经过渠道认证的内部事件；无普通用户可控制的 HTTP 入口。
     * @author c1342
     */
    @Transactional(isolation = org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public PaymentVO handleChannelEvent(PaymentChannelEvent event) {
        validateEvent(event);
        Payment hint = paymentMapper.selectOne(new QueryWrapper<Payment>().eq("payment_no", event.paymentNo()));
        if (hint == null) throw new ResourceNotFoundException(ErrorCode.PAYMENT_NOT_FOUND);
        Order order = lockOrder(hint.getOrderId());
        List<Payment> payments = paymentMapper.lockByOrder(order.getId());
        Payment payment = payments.stream().filter(p -> p.getId().equals(hint.getId())).findFirst().orElseThrow(PaymentServiceImpl::conflict);
        byte[] hash = digest(json.writeValueAsString(List.of(event.provider(), event.eventId(), event.paymentNo(),
                event.attemptId(), event.eventType(), event.amount().stripTrailingZeros().toPlainString(), event.currency(), event.successful())));
        byte[] prior = settlement.notificationHash(event.provider(), event.eventId());
        if (prior != null) {
            if (!MessageDigest.isEqual(prior, hash)) throw new ConflictException(ErrorCode.PAYMENT_IDEMPOTENCY_CONFLICT);
            return PaymentVO.from(payment);
        }
        amount(order, payment);
        if (payment.getAmount().compareTo(event.amount()) != 0 || !Objects.equals(payment.getCurrency(), event.currency())) {
            throw new BusinessRuleException(ErrorCode.PAYMENT_AMOUNT_MISMATCH);
        }
        Instant now = clock.instant();
        PaymentStatus before = payment.getStatus();
        boolean sameAttempt = event.attemptId().equals(settlement.attempt(payment.getId()));
        // 旧尝试失败只能确认；成功收款不能丢弃或错误归属到新尝试。
        if (!sameAttempt && !settlement.knownAttempt(payment.getId(), event.attemptId())) throw conflict();
        if ((sameAttempt || event.successful()) && before != PaymentStatus.SUCCESS) {
            if (event.successful()) {
                if (payments.stream().anyMatch(p -> p.getStatus() == PaymentStatus.SUCCESS)) throw conflict();
                {
                    // 已知旧尝试的成功也是有效收款，按订单实际截止结算，不能仅因当前尝试变化丢弃。
                    Instant deadline = settlement.deadline(order.getId());
                    if (order.getStatus() == OrderStatus.WAIT_PAY && now.isBefore(deadline)) {
                        settlement.settleSeats(order.getId(), true, now);
                        changeOrder(order, OrderStatus.PAID, now);
                        settlement.issueTickets(order.getId(), now);
                    } else if (order.getStatus() == OrderStatus.CANCELLED || order.getStatus() == OrderStatus.EXPIRED
                            || (order.getStatus() == OrderStatus.WAIT_PAY && !now.isBefore(deadline))) {
                        settleLateSuccess(order, payment, now);
                    } else throw conflict();
                }
                transition(payment, PaymentStatus.SUCCESS);
            } else if (before == PaymentStatus.PENDING) {
                transition(payment, PaymentStatus.FAILED);
            }
        }
        settlement.notification(event, hash, now);
        audit(payment, null, event.successful() ? "CHANNEL_SUCCESS"
                : (sameAttempt ? "CHANNEL_FAILURE" : "STALE_CHANNEL_FAILURE"), before, "CHANNEL", event.eventId());
        return PaymentVO.from(payment);
    }

    private void settleLateSuccess(Order order, Payment payment, Instant now) {
        settlement.settleSeats(order.getId(), false, now);
        if (order.getStatus() == OrderStatus.WAIT_PAY) changeOrder(order, OrderStatus.EXPIRED, now);
        if (order.getStatus() == OrderStatus.CANCELLED) {
            audit(payment, null, "CANCELLED_LATE_COMPENSATION", payment.getStatus(), "SYSTEM", null);
        }
        if (order.getStatus() != OrderStatus.REFUNDING) changeOrder(order, OrderStatus.REFUNDING, now);
        if (refundMapper.selectOne(new QueryWrapper<Refund>().eq("payment_id", payment.getId())) == null) {
            Refund refund = new Refund();
            refund.setId(IdWorker.getId());
            refund.setRefundNo("R" + token());
            refund.setOrderId(order.getId());
            refund.setPaymentId(payment.getId());
            refund.setAmount(payment.getAmount());
            refund.setStatus(RefundStatus.PENDING);
            refund.setSource("LATE_PAYMENT");
            refund.setAttemptNo(1);
            refund.setNextRunAt(now);
            refund.setCreateTime(now);
            refund.setUpdateTime(now);
            PaymentSettlementRepository.check(refundMapper.insert(refund));
            settlement.refundIntent(refund.getId(), now);
        }
    }

    private Payment newPayment(Order order) {
        Payment payment = new Payment();
        payment.setId(IdWorker.getId());
        payment.setPaymentNo("P" + token());
        payment.setOrderId(order.getId());
        payment.setAmount(order.getTotalAmount());
        payment.setCurrency("CNY");
        payment.setStatus(PaymentStatus.PENDING);
        payment.setVersion(0);
        payment.setAttemptId(token());
        payment.setCreateTime(clock.instant());
        payment.setUpdateTime(payment.getCreateTime());
        PaymentSettlementRepository.check(paymentMapper.insert(payment));
        return payment;
    }
    private void startAttempt(Payment payment, Long operator, String source, String operation) {
        String attempt = token();
        settlement.attempt(payment.getId(), attempt);
        payment.setAttemptId(attempt);
        PaymentAudit attemptAudit = new PaymentAudit();
        attemptAudit.setId(IdWorker.getId());
        attemptAudit.setPaymentId(payment.getId());
        attemptAudit.setOrderId(payment.getOrderId());
        attemptAudit.setOperatorId(operator);
        attemptAudit.setOperation("ATTEMPT_STARTED");
        attemptAudit.setToStatus(PaymentStatus.PENDING.getCode());
        attemptAudit.setSource(source);
        attemptAudit.setRemark(attempt);
        attemptAudit.setCreateTime(clock.instant());
        PaymentSettlementRepository.check(auditMapper.insert(attemptAudit));
        audit(payment, operator, operation, null, source, null);
        gateway.initiate(payment.getPaymentNo(), attempt, payment.getAmount(), payment.getCurrency());
    }
    private void transition(Payment payment, PaymentStatus after) {
        Instant now = clock.instant();
        PaymentSettlementRepository.check(paymentMapper.transition(payment.getId(), payment.getVersion(),
                payment.getStatus().getCode(), after.getCode(), now));
        payment.setStatus(after);
        payment.setVersion(payment.getVersion() + 1);
        payment.setUpdateTime(now);
    }
    private void changeOrder(Order order, OrderStatus after, Instant now) {
        settlement.orderState(order.getId(), order.getVersion(), order.getStatus().getCode(), after.getCode(), now);
        order.setStatus(after);
        order.setVersion(order.getVersion() + 1);
    }
    private void ensurePayable(Order order) {
        if (order.getStatus() != OrderStatus.WAIT_PAY || !clock.instant().isBefore(settlement.deadline(order.getId()))) {
            throw new ConflictException(ErrorCode.PAYMENT_ORDER_NOT_PAYABLE);
        }
        if (order.getTotalAmount() == null || order.getTotalAmount().signum() < 0) {
            throw new BusinessRuleException(ErrorCode.PAYMENT_AMOUNT_MISMATCH);
        }
    }
    private PaymentVO replay(long operator, String operation, String key, Long target) {
        String hash = HexFormat.of().formatHex(digest(operation + ":" + target));
        Instant now = clock.instant();
        var row = idempotencyMapper.find(operator, operation, key, true);
        if (row != null && !row.expiresAt().isAfter(now)) {
            idempotencyMapper.delete(operator, operation, key);
            row = null;
        }
        if (row == null) {
            idempotencyMapper.reserve(operator, operation, key, hash, now);
            row = idempotencyMapper.find(operator, operation, key, true);
        }
        if (row == null) throw new IllegalStateException("支付幂等占位未保存");
        if (!row.digest().equals(hash)) throw new ConflictException(ErrorCode.PAYMENT_IDEMPOTENCY_CONFLICT);
        return row.responseJson() == null ? null : json.readValue(row.responseJson(), PaymentVO.class);
    }
    private PaymentVO save(long operator, String operation, String key, Payment payment) {
        PaymentVO result = PaymentVO.from(payment);
        PaymentSettlementRepository.check(idempotencyMapper.save(operator, operation, key, json.writeValueAsString(result), clock.instant()));
        return result;
    }
    private void audit(Payment payment, Long operator, String operation, PaymentStatus before, String source, String eventId) {
        PaymentAudit audit = new PaymentAudit();
        audit.setId(IdWorker.getId());
        audit.setPaymentId(payment.getId());
        audit.setOrderId(payment.getOrderId());
        audit.setOperatorId(operator);
        audit.setOperation(operation);
        audit.setFromStatus(before == null ? null : before.getCode());
        audit.setToStatus(payment.getStatus().getCode());
        audit.setSource(source);
        audit.setEventId(eventId);
        audit.setCreateTime(clock.instant());
        PaymentSettlementRepository.check(auditMapper.insert(audit));
    }
    private Order lockOrder(Long id) {
        requireId(id);
        Order order = orderMapper.lockById(id);
        if (order == null) throw new ResourceNotFoundException(ErrorCode.RESOURCE_NOT_FOUND);
        return order;
    }
    private Payment requirePayment(Long id) {
        requireId(id);
        Payment payment = paymentMapper.selectById(id);
        if (payment == null) throw new ResourceNotFoundException(ErrorCode.PAYMENT_NOT_FOUND);
        return payment;
    }
    private static void requireOwner(Order order, Long user) {
        if (user == null || !user.equals(order.getUserId())) throw new ResourceNotFoundException(ErrorCode.RESOURCE_NOT_FOUND);
    }
    private static void amount(Order order, Payment payment) {
        if (payment.getAmount() == null || order.getTotalAmount() == null
                || payment.getAmount().compareTo(order.getTotalAmount()) != 0 || !"CNY".equals(payment.getCurrency())) {
            throw new BusinessRuleException(ErrorCode.PAYMENT_AMOUNT_MISMATCH);
        }
    }
    private static void requireId(Long id) {
        if (id == null || id <= 0) throw new InvalidRequestException(ErrorCode.INVALID_ARGUMENT);
    }
    private static void requireKey(String key) {
        if (key == null || !key.matches("[\\x21-\\x7E]{1,128}")) throw new InvalidRequestException(ErrorCode.INVALID_ARGUMENT);
    }
    private static void validateEvent(PaymentChannelEvent e) {
        if (e == null || !text(e.provider(), 32) || !text(e.eventId(), 128) || !text(e.paymentNo(), 64)
                || !text(e.attemptId(), 64) || !text(e.eventType(), 32) || e.amount() == null
                || e.amount().signum() < 0 || !"CNY".equals(e.currency())) throw new InvalidRequestException(ErrorCode.INVALID_ARGUMENT);
    }
    private static boolean text(String value, int max) { return value != null && !value.isBlank() && value.length() <= max; }
    private static byte[] digest(String value) {
        try { return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)); }
        catch (java.security.NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
    }
    private static String token() { return UUID.randomUUID().toString().replace("-", ""); }
    private static ConflictException conflict() { return new ConflictException(ErrorCode.PAYMENT_STATE_CONFLICT); }
    private static String statusValue(Integer code) {
        if (code == null) return null;
        for (PaymentStatus status : PaymentStatus.values()) if (status.getCode() == code) return status.getValue();
        throw new IllegalStateException("支付审计状态不合法");
    }
}
