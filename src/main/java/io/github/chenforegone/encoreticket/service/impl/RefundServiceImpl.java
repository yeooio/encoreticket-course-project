package io.github.chenforegone.encoreticket.service.impl;

import io.github.chenforegone.encoreticket.service.codec.RefundJsonCodec;
import io.github.chenforegone.encoreticket.gateway.RefundGateway;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import io.github.chenforegone.encoreticket.common.error.ErrorCode;
import io.github.chenforegone.encoreticket.common.exception.*;
import io.github.chenforegone.encoreticket.common.response.PageResponse;
import io.github.chenforegone.encoreticket.mapper.RefundProcessingMapper;
import io.github.chenforegone.encoreticket.mapper.RefundProcessingMapper.*;
import io.github.chenforegone.encoreticket.model.vo.*;
import io.github.chenforegone.encoreticket.service.*;
import io.github.chenforegone.encoreticket.common.exception.RefundException;
import io.github.chenforegone.encoreticket.common.time.Clock;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;

/**
 * 退款受理、持久化任务和结果联动；外部调用始终处于业务事务之外。
 * @author c1342
 */
@Service
public class RefundServiceImpl implements RefundService {
    public static final String APPLY="refund-apply:v1", PROCESS="refund-process:v1";
    private static final org.slf4j.Logger log=org.slf4j.LoggerFactory.getLogger(RefundServiceImpl.class);
    private final RefundProcessingMapper mapper;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final RefundJsonCodec codec;
    private final RefundGateway gateway;
    public RefundServiceImpl(RefundProcessingMapper mapper,PlatformTransactionManager manager,Clock clock,JsonMapper json,RefundGateway gateway) {
        this.mapper=mapper; this.clock=clock; this.codec=new RefundJsonCodec(json); this.gateway=gateway;
        tx=new TransactionTemplate(manager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }
    @Override public RefundVO apply(Long orderId,Long userId,String key) {
        id(orderId); id(userId); key(key);
        return tx.execute(s->{
            OrderData order=lockOrder(orderId); owner(order,userId);
            RefundVO replay=replay(userId,APPLY,key,orderId+"",clock.instant());
            if(replay!=null) return replay;
            List<RefundVO> prior=mapper.refunds(orderId);
            if(!prior.isEmpty()) return save(userId,APPLY,key,prior.getFirst());
            require(order.status()==1,ErrorCode.REFUND_STATE_CONFLICT);
            List<TicketData> tickets=lockTickets(orderId);
            require(tickets.stream().noneMatch(t->t.status()==1),ErrorCode.TICKET_ALREADY_USED);
            Instant now=clock.instant();
            require(now.isBefore(order.start().atZone(ZoneId.of("Asia/Shanghai")).toInstant().minusSeconds(900)),ErrorCode.REFUND_CUTOFF_REACHED);
            PaymentData payment=successfulPayment(orderId); validateMoney(order,payment);
            validateTickets(orderId,tickets,true); validateSeats(orderId);
            long refund=IdWorker.getId();
            check(mapper.insertRefund(refund,"R"+token(),orderId,payment.id(),payment.amount(),utc(now)));
            createAttempt(refund,1,now); check(mapper.acceptOrder(orderId,utc(now)));
            audit(refund,"USER_ACCEPTED",userId,now);
            return save(userId,APPLY,key,byId(refund));
        });
    }
    @Override public OrderRefundsVO byOrder(Long orderId,Long userId) {
        id(orderId); id(userId);
        return tx.execute(s->{ OrderData order=order(orderId,false); owner(order,userId);
            return new OrderRefundsVO(orderId.toString(),orderStatus(order.status()),mapper.refunds(orderId)); });
    }
    @Override public RefundVO adminProcess(Long refundId,Long operator,String key,String action) {
        id(refundId); id(operator); key(key);
        if(!Set.of("RETRY","CHECK").contains(action)) throw new InvalidRequestException(ErrorCode.INVALID_ARGUMENT);
        return tx.execute(s->{
            RefundVO hint=byId(refundId); OrderData order=lockOrder(Long.parseLong(hint.orderId()));
            RefundVO replay=replay(operator,PROCESS,key,refundId+":"+action,clock.instant());
            if(replay!=null) return replay;
            RefundVO current=byId(refundId); require(order.status()==4,ErrorCode.REFUND_STATE_CONFLICT);
            Instant now=clock.instant();
            if(action.equals("RETRY")) {
                require(current.status().equals("FAILED"),ErrorCode.REFUND_STATE_CONFLICT);
                int attempt=mapper.attemptNumber(refundId)+1;
                createAttempt(refundId,attempt,now); check(mapper.retry(refundId,attempt,utc(now)));
            } else { require(current.status().equals("PENDING"),ErrorCode.REFUND_STATE_CONFLICT); check(mapper.scheduleCheck(refundId,utc(now))); }
            audit(refundId,"ADMIN_"+action,operator,now);
            return save(operator,PROCESS,key,byId(refundId));
        });
    }
    @Override public PageResponse<RefundVO> adminPage(int page,int size,String status,String source,Long orderId,Instant from,Instant to) {
        if(page<1||size<1||size>100||(long)(page-1)*size>Integer.MAX_VALUE||(from!=null&&to!=null&&!from.isBefore(to))) throw new InvalidRequestException(ErrorCode.INVALID_ARGUMENT);
        if(status!=null&&!Set.of("PENDING","SUCCESS","FAILED").contains(status)) throw new InvalidRequestException(ErrorCode.INVALID_ARGUMENT);
        if(source!=null&&!Set.of("USER","LATE_PAYMENT","LEGACY").contains(source)) throw new InvalidRequestException(ErrorCode.INVALID_ARGUMENT);
        if(orderId!=null) id(orderId);
        Integer state=status==null?null:switch(status){case "PENDING"->0;case "SUCCESS"->1;default->2;};
        return mapper.page(page,size,state,source,orderId,from==null?null:utc(from),to==null?null:utc(to));
    }
    @Override public RefundDetailVO adminDetail(Long refundId) { id(refundId); return tx.execute(s->new RefundDetailVO(byId(refundId),mapper.attempts(refundId),mapper.audits(refundId))); }
    public int runPending(int limit) {
        if(limit<1||limit>500) throw new InvalidRequestException(ErrorCode.INVALID_ARGUMENT);
        List<Long> ids=mapper.pending(utc(clock.instant()),limit); int processed=0;
        for(Long refund:ids) {
            try { if(processOne(refund)) processed++; }
            catch(RuntimeException e) { log.warn("退款恢复待核查 refundId={} diagnostic={}",refund,ExceptionLogSupport.safeStackTrace(e)); }
        }
        return processed;
    }
    public boolean processOne(long refundId) {
        Work work;
        try { work=tx.execute(s->claim(refundId)); }
        catch(RuntimeException e) {
            // 领取事务已回滚，独立提交固定五分钟退避；不修改资金状态，不抢占其他工作者的新租约。
            try { tx.executeWithoutResult(s->{ Instant now=clock.instant(); mapper.deferInvalidClaim(refundId,utc(now),utc(now.plusSeconds(300))); }); }
            catch(RuntimeException persistence) { log.warn("退款退避持久化失败 refundId={} diagnostic={}",refundId,ExceptionLogSupport.safeStackTrace(persistence)); }
            throw e;
        }
        if(work==null) return false;
        RefundGateway.Result result;
        try {
            result=gateway.query(work.refundNo,work.attempt,work.paymentNo,work.amount);
            // 历史首轮未知不可重发；明确 FAILED 后由管理员授权的新轮次可以执行。
            if(result==RefundGateway.Result.NOT_FOUND&&work.submitAllowed) result=gateway.submit(work.refundNo,work.attempt,work.paymentNo,work.amount);
        } catch(RuntimeException e) {
            log.warn("退款渠道结果未知 refundId={} attemptId={} diagnostic={}",work.refundId,work.attempt,ExceptionLogSupport.safeStackTrace(e));
            result=RefundGateway.Result.UNKNOWN;
        }
        RefundGateway.Result confirmed=result; tx.executeWithoutResult(s->finish(work,confirmed)); return true;
    }
    private Work claim(long refundId) {
        RefundVO hint=byId(refundId); OrderData order=lockOrder(Long.parseLong(hint.orderId()));
        RefundVO current=byId(refundId); if(!current.status().equals("PENDING")) return null;
        require(order.status()==4,ErrorCode.INTERNAL_ERROR); Instant now=clock.instant(); String lease=token();
        if(mapper.claim(refundId,lease,utc(now),utc(now.plusSeconds(60)))!=1) return null;
        int attempt=mapper.attemptNumber(refundId);
        if(mapper.attemptCount(refundId,attempt)==0) createAttempt(refundId,attempt,now);
        check(mapper.markSubmitted(refundId,attempt,utc(now)));
        PaymentData payment=successfulPayment(order.id()); validateMoney(order,payment);
        require(payment.id()==Long.parseLong(current.paymentId())&&payment.amount().compareTo(new BigDecimal(current.amount()))==0,ErrorCode.INTERNAL_ERROR);
        audit(refundId,"CHANNEL_CHECK",null,now);
        boolean submitAllowed=!current.source().equals("LEGACY")||(attempt>1&&mapper.previousAttemptFailed(refundId,attempt));
        return new Work(refundId,order.id(),current.refundNo(),attempt,payment.paymentNo(),payment.amount(),submitAllowed,lease);
    }
    private void finish(Work work,RefundGateway.Result result) {
        OrderData order=lockOrder(work.orderId); RefundVO refund=byId(work.refundId);
        if(refund.status().equals("SUCCESS")) return;
        require(mapper.attemptCount(work.refundId,work.attempt)==1,ErrorCode.INTERNAL_ERROR);
        PaymentData payment=successfulPayment(order.id()); validateMoney(order,payment);
        require(refund.refundNo().equals(work.refundNo)&&payment.paymentNo().equals(work.paymentNo)&&payment.id()==Long.parseLong(refund.paymentId())&&payment.amount().compareTo(work.amount)==0&&payment.amount().compareTo(new BigDecimal(refund.amount()))==0,ErrorCode.INTERNAL_ERROR);
        Instant now=clock.instant(); int currentAttempt=mapper.attemptNumber(work.refundId);
        if(result==RefundGateway.Result.SUCCESS) {
            require(order.status()==4,ErrorCode.INTERNAL_ERROR); List<TicketData> tickets=lockTickets(order.id());
            require(tickets.stream().noneMatch(t->t.status()==1),ErrorCode.INTERNAL_ERROR);
            if(refund.source().equals("USER")) { validateTickets(order.id(),tickets,false); validateSeats(order.id()); }
            else require(tickets.stream().allMatch(t->t.status()==0||t.status()==2),ErrorCode.INTERNAL_ERROR);
            mapper.voidTickets(order.id(),utc(now)); mapper.releaseSeats(order.id(),utc(now));
            check(mapper.completeOrder(order.id(),utc(now))); check(mapper.succeed(work.refundId,utc(now)));
            check(mapper.finishAttempt(work.refundId,work.attempt,"SUCCESS",utc(now))); audit(work.refundId,"CHANNEL_SUCCESS",null,now);
        } else if(result==RefundGateway.Result.FAILED&&currentAttempt==work.attempt) {
            check(mapper.fail(work.refundId,utc(now))); check(mapper.finishAttempt(work.refundId,work.attempt,"FAILED",utc(now))); audit(work.refundId,"CHANNEL_FAILED",null,now);
        } else mapper.releaseLease(work.refundId,work.lease,utc(now.plusSeconds(60)));
    }
    private OrderData lockOrder(long id) { OrderData hint=order(id,false); require(mapper.lockScreening(hint.screeningId()),ErrorCode.INTERNAL_ERROR); OrderData locked=order(id,true); require(locked.screeningId()==hint.screeningId(),ErrorCode.INTERNAL_ERROR); return locked; }
    private OrderData order(long id,boolean lock) { var rows=mapper.order(id,lock); if(rows.isEmpty()) throw new ResourceNotFoundException(ErrorCode.RESOURCE_NOT_FOUND); return rows.getFirst(); }
    private PaymentData successfulPayment(long orderId) { var rows=mapper.successfulPayments(orderId); require(rows.size()==1,ErrorCode.INTERNAL_ERROR); return rows.getFirst(); }
    private void validateMoney(OrderData order,PaymentData payment) { BigDecimal total=mapper.itemTotal(order.id()); require(order.start()!=null&&order.amount()!=null&&order.amount().signum()>0&&total!=null&&order.amount().compareTo(total)==0&&order.amount().compareTo(payment.amount())==0&&"CNY".equals(payment.currency()),ErrorCode.INTERNAL_ERROR); }
    private List<TicketData> lockTickets(long orderId) { List<TicketData> result=new ArrayList<>(); for(Long id:mapper.ticketIds(orderId)) result.add(mapper.lockTicket(id)); return result; }
    private void validateTickets(long orderId,List<TicketData> tickets,boolean accepting) { List<Long> items=mapper.itemIds(orderId); require(!items.isEmpty()&&items.equals(tickets.stream().map(TicketData::item).sorted().toList())&&tickets.stream().allMatch(t->t.status()==0||(!accepting&&t.status()==2)),ErrorCode.INTERNAL_ERROR); }
    private void validateSeats(long orderId) {
        var seats=mapper.lockSeats(orderId); require(!seats.isEmpty(),ErrorCode.INTERNAL_ERROR);
        for(var seat:seats) require(seat.get("id")!=null&&seat.get("order_id") instanceof Number owner&&owner.longValue()==orderId&&((Number)seat.get("status")).intValue()==2&&Objects.equals(seat.get("screening_id"),seat.get("expected")),ErrorCode.INTERNAL_ERROR);
        require(mapper.ownedSeatCount(orderId)==seats.size(),ErrorCode.INTERNAL_ERROR);
    }
    private RefundVO byId(long id) { var rows=mapper.refund(id); if(rows.isEmpty()) throw new ResourceNotFoundException(ErrorCode.RESOURCE_NOT_FOUND); return rows.getFirst(); }
    private RefundVO replay(long user,String operation,String key,String target,Instant now) {
        String digest=digest(operation+":"+target); mapper.reserveSnapshot(user,operation,key,digest,utc(now),utc(now.plusSeconds(86400)));
        var row=mapper.lockSnapshot(user,operation,key); require(digest.equals(row.get("request_digest")),ErrorCode.IDEMPOTENCY_CONFLICT);
        return row.get("response_json")==null?null:codec.read(row.get("response_json").toString());
    }
    private RefundVO save(long user,String operation,String key,RefundVO result) { check(mapper.saveSnapshot(user,operation,key,codec.write(result))); return result; }
    private void createAttempt(long refund,int attempt,Instant now) { check(mapper.createAttempt(refund,attempt,utc(now))); }
    private void audit(long refund,String action,Long user,Instant now) { check(mapper.audit(IdWorker.getId(),refund,action,user,utc(now))); }
    public int cleanup() { return tx.execute(s->mapper.cleanup(APPLY,PROCESS,utc(clock.instant()))); }
    private static void owner(OrderData order,Long user) { if(order.userId()!=user) throw new ResourceNotFoundException(ErrorCode.RESOURCE_NOT_FOUND); }
    private static void id(Long id) { if(id==null||id<=0) throw new InvalidRequestException(ErrorCode.INVALID_ARGUMENT); }
    private static void key(String key) { if(key==null||!key.matches("[A-Za-z0-9_-]{1,128}")) throw new InvalidRequestException(ErrorCode.INVALID_ARGUMENT); }
    private static void check(int n) { require(n==1,ErrorCode.INTERNAL_ERROR); }
    private static void require(boolean ok,ErrorCode error) { if(!ok) throw new RefundException(error); }
    private static LocalDateTime utc(Instant now) { return LocalDateTime.ofInstant(now,ZoneOffset.UTC); }
    private static String token() { return UUID.randomUUID().toString().replace("-",""); }
    private static String digest(String input) { try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8))); } catch(java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); } }
    private static String orderStatus(int status) { return switch(status) { case 0->"PENDING_PAYMENT"; case 1->"PAID"; case 2->"CANCELLED"; case 3->"EXPIRED"; case 4->"REFUNDING"; case 5->"REFUNDED"; default->throw new IllegalStateException("订单状态异常"); }; }
    /**
     * 已提交领取意图，不包含数据库锁。
     * @author c1342
     */
    private record Work(long refundId,long orderId,String refundNo,int attempt,String paymentNo,BigDecimal amount,boolean submitAllowed,String lease) { }
}
