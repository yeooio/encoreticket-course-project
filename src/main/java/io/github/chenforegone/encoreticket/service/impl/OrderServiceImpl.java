package io.github.chenforegone.encoreticket.service.impl;

import io.github.chenforegone.encoreticket.common.time.Clock;
import io.github.chenforegone.encoreticket.service.rules.ScreeningRules;

import io.github.chenforegone.encoreticket.mapper.OrderWorkflowMapper;
import io.github.chenforegone.encoreticket.mapper.PaymentIdempotencyMapper;
import io.github.chenforegone.encoreticket.model.dto.CreateOrderRequest;
import io.github.chenforegone.encoreticket.model.vo.OrderDetailVO;
import io.github.chenforegone.encoreticket.service.*;
import io.github.chenforegone.encoreticket.common.exception.OrderExpiredException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * 本人订单事务编排。
 * @author c1342
 */
@Service
public class OrderServiceImpl implements OrderService {
    private final OrderWorkflowMapper mapper;
    private final TransactionTemplate transaction;
    private final Clock clock;
    private final PaymentIdempotencyMapper snapshots;
    private final JsonMapper json;
    public OrderServiceImpl(OrderWorkflowMapper mapper,PaymentIdempotencyMapper snapshots,
            PlatformTransactionManager manager,Clock clock,JsonMapper json) {
        this.mapper=mapper;
        this.snapshots=snapshots;
        this.json=json;
        this.clock=clock;
        this.transaction=new TransactionTemplate(manager);
        this.transaction.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.transaction.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_READ_COMMITTED);
    }
    @org.springframework.scheduling.annotation.Scheduled(fixedDelayString="${encore.order.cleanup-delay-ms:60000}")
    public void cleanupSnapshots() {
        transaction.executeWithoutResult(status->mapper.cleanup(clock.instant()));
    }
    @Override public io.github.chenforegone.encoreticket.common.response.PageResponse<io.github.chenforegone.encoreticket.model.vo.OrderSummaryVO> list(Long user,io.github.chenforegone.encoreticket.model.dto.OrderQueryRequest query) {
        var q=query==null?new io.github.chenforegone.encoreticket.model.dto.OrderQueryRequest(null,null,null,null,null):query;
        int page=q.page()==null?1:q.page(), size=q.pageSize()==null?20:q.pageSize();
        if(page<1||size<1||size>100||q.status()!=null&&!java.util.Set.of("PENDING_PAYMENT","PAID","CANCELLED","EXPIRED","REFUNDING","REFUNDED").contains(q.status()))
            throw new io.github.chenforegone.encoreticket.common.exception.InvalidRequestException(io.github.chenforegone.encoreticket.common.error.ErrorCode.INVALID_ARGUMENT);
        var now=clock.instant();
        mapper.validateOwned(user);
        return mapper.page(user,page,size,q.status(),q.screeningId()==null?null:ScreeningRules.parseId(q.screeningId()),q.orderNo(),now);
    }
    @Override public OrderDetailVO detail(Long user,String id) {
        return mapper.detail(ScreeningRules.parseId(id),user,clock.instant());
    }
    @Override public io.github.chenforegone.encoreticket.model.vo.OrderCancelledVO cancel(Long user,String id) {
        long order=ScreeningRules.parseId(id);
        var result=transaction.execute(status->{
            int state=mapper.lockOwned(order,user);
            var now=clock.instant();
            if(state==0&&!now.isBefore(mapper.deadline(order))) { mapper.expire(order,now); return null; }
            if(state==0&&mapper.pendingPayment(order)) throw new io.github.chenforegone.encoreticket.common.exception.ConflictException(io.github.chenforegone.encoreticket.common.error.ErrorCode.PAYMENT_RESULT_UNKNOWN);
            if(state==0) mapper.cancel(order,now);
            else if(state==3) throw new OrderExpiredException();
            else if(state!=2) throw new io.github.chenforegone.encoreticket.common.exception.ConflictException(io.github.chenforegone.encoreticket.common.error.ErrorCode.RESOURCE_CONFLICT);
            return new io.github.chenforegone.encoreticket.model.vo.OrderCancelledVO(id,"CANCELLED");
        });
        if(result==null) throw new OrderExpiredException();
        return result;
    }
    @Override public OrderDetailVO create(Long user,String key,CreateOrderRequest request) {
        if(user==null||user<=0||key==null||!key.matches("[A-Za-z0-9_-]{1,128}")||request==null
                ||request.screeningSeatIds()==null||request.screeningSeatIds().isEmpty()||request.screeningSeatIds().size()>6
                ||new java.util.HashSet<>(request.screeningSeatIds()).size()!=request.screeningSeatIds().size())
            throw new io.github.chenforegone.encoreticket.common.exception.InvalidRequestException(io.github.chenforegone.encoreticket.common.error.ErrorCode.INVALID_ARGUMENT);
        long screening=ScreeningRules.parseId(request.screeningId());
        var seats=request.screeningSeatIds().stream().map(ScreeningRules::parseId).sorted().toList();
        String operation="order-create:v1";
        String digest;
        try {
            digest=java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest((screening+":"+seats).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch(java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
        while(true) {
            var created=transaction.execute(status->{
                mapper.lockUser(user);
                var now=clock.instant();
                var prior=snapshots.find(user,operation,key,true);
                if(prior!=null&&!prior.expiresAt().isAfter(now)) { snapshots.delete(user,operation,key); prior=null; }
                if(prior!=null) {
                    if(!prior.digest().equals(digest)) throw new io.github.chenforegone.encoreticket.common.exception.ConflictException(io.github.chenforegone.encoreticket.common.error.ErrorCode.IDEMPOTENCY_CONFLICT);
                    return json.readValue(prior.responseJson(),OrderDetailVO.class);
                }
                mapper.lockPurchase(screening);
                now=clock.instant();
                boolean expired=false;
                for(long pending:mapper.pendingOrders(user,screening)) {
                    if(!now.isBefore(mapper.deadline(pending))) {
                        mapper.expire(pending,now);
                        expired=true;
                    }
                }
                // 先提交过期释放，再以同一锁顺序重新校验新购，避免新购失败回滚旧单。
                if(expired) return null;
                snapshots.reserve(user,operation,key,digest,now);
                mapper.requireNoLiveOrder(user,screening);
                mapper.requirePurchasable(screening,seats,now);
                now=clock.instant();
                mapper.requirePurchasable(screening,seats,now);
                long id=mapper.insert(user,screening,seats,now);
                var result=mapper.detail(id,user,now);
                snapshots.save(user,operation,key,json.writeValueAsString(result),now);
                return result;
            });
            if(created!=null) return created;
        }
    }
}
