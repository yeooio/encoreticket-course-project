package io.github.chenforegone.encoreticket.service.transaction;

import io.github.chenforegone.encoreticket.common.time.Clock;
import io.github.chenforegone.encoreticket.service.rules.TicketVerificationRules;

import io.github.chenforegone.encoreticket.common.exception.TicketVerificationException;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import io.github.chenforegone.encoreticket.common.error.ErrorCode;
import io.github.chenforegone.encoreticket.converter.TicketVerificationConverter;
import io.github.chenforegone.encoreticket.mapper.TicketVerificationMapper;
import io.github.chenforegone.encoreticket.model.query.TicketVerificationProjection;
import io.github.chenforegone.encoreticket.model.vo.TicketVerificationVO;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.cfg.DateTimeFeature;
import tools.jackson.databind.json.JsonMapper;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import io.github.chenforegone.encoreticket.model.enums.TicketStatus;
import io.github.chenforegone.encoreticket.model.enums.ScreeningStatus;

/**
 * 每次尝试及竞争失败后的重放均使用全新事务。
 *
 * @author c1342
 */
@Component
public class TicketVerificationTransaction {
    public static final String OPERATION = "ticket-verification:v1";

    /**
     * 核销订单投影状态，避免依赖并行支付模块的模型。
     * @author c1342
     */
    private enum TicketVerificationOrderState {
        WAIT_PAY(0), PAID(1), CANCELLED(2), EXPIRED(3), REFUNDING(4), REFUNDED(5);
        private final int dbValue;
        TicketVerificationOrderState(int dbValue) { this.dbValue=dbValue; }
    }
    private final TicketVerificationMapper mapper;
    private final TicketVerificationConverter converter;
    private final Clock clock;
    private final JsonMapper json;
    private final TransactionTemplate transaction;

    public TicketVerificationTransaction(TicketVerificationMapper mapper, TicketVerificationConverter converter,
            Clock clock, JsonMapper json, PlatformTransactionManager manager) {
        this.mapper=mapper; this.converter=converter; this.clock=clock;
        // 快照保留接口业务时间的偏移，不受共享 mapper 的上下文时区影响。
        this.json=json.rebuild().disable(DateTimeFeature.ADJUST_DATES_TO_CONTEXT_TIME_ZONE,
                DateTimeFeature.WRITE_DATES_WITH_CONTEXT_TIME_ZONE).build();
        transaction=new TransactionTemplate(manager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    /**
     * 仅标识幂等占位唯一键竞争，不误判其他唯一约束。
     * @author c1342
     */
    public static class ReservationCollision extends RuntimeException {
        public ReservationCollision() { }
        public ReservationCollision(Throwable cause) { super(cause); }
    }

    public TicketVerificationVO replay(long operator,String key,String digest) {
        return transaction.execute(status -> {
            require(mapper.operatorExists(operator),ErrorCode.INTERNAL_ERROR);
            return historical(mapper.find(operator,OPERATION,key,true),digest,clock.instant());
        });
    }

    public TicketVerificationVO execute(long operator,String key,String digest,String code,long screeningId) {
        return transaction.execute(status -> {
            require(mapper.operatorExists(operator),ErrorCode.INTERNAL_ERROR);
            Instant now=clock.instant();
            var existing=mapper.find(operator,OPERATION,key,false);
            if (existing!=null) {
                existing=mapper.find(operator,OPERATION,key,true);
                now=clock.instant();
                TicketVerificationVO replay=historical(existing,digest,now);
                if (replay!=null) { return replay; }
                if (existing!=null) { mapper.delete(operator,OPERATION,key); }
            }
            try { mapper.reserve(operator,OPERATION,key,digest,now); }
            catch (DuplicateKeyException e) { throw new ReservationCollision(e); }

            var screening=mapper.lockScreening(screeningId);
            require(screening!=null,ErrorCode.RESOURCE_NOT_FOUND);
            var initial=mapper.byCode(code);
            require(initial!=null,ErrorCode.RESOURCE_NOT_FOUND);
            TicketVerificationRules.validate(initial);
            require(initial.screeningId()==screeningId,ErrorCode.TICKET_SCREENING_MISMATCH);
            Integer orderStatus=mapper.lockOrder(initial.orderId());
            Integer ticketStatus=mapper.lockTicket(initial.ticketId());
            var current=mapper.byId(initial.ticketId());
            TicketVerificationRules.validate(current);
            require(Objects.equals(initial.orderId(),current.orderId())
                    && Objects.equals(initial.itemId(),current.itemId())
                    && current.screeningId()==screeningId && code.equals(current.ticketCode()),ErrorCode.INTERNAL_ERROR);
            require(orderStatus!=null && ticketStatus!=null,ErrorCode.INTERNAL_ERROR);
            require(Objects.equals(orderStatus,current.orderStatus()) && Objects.equals(ticketStatus,current.ticketStatus()),ErrorCode.INTERNAL_ERROR);
            require(ticketStatus>=0 && ticketStatus<=2 && orderStatus>=0 && orderStatus<=5,ErrorCode.INTERNAL_ERROR);
            TicketStatus ticketState=TicketStatus.fromDbValue(ticketStatus);
            require((ticketState==TicketStatus.USED)==(current.verificationId()!=null),ErrorCode.INTERNAL_ERROR);
            if (ticketState==TicketStatus.USED) { TicketVerificationRules.validateSuccess(current); }
            require(ticketState!=TicketStatus.USED,ErrorCode.TICKET_ALREADY_USED);
            require(ticketState!=TicketStatus.VOID,ErrorCode.TICKET_VOID);
            require(current.verificationId()==null,ErrorCode.INTERNAL_ERROR);
            require(orderStatus==TicketVerificationOrderState.PAID.dbValue,ErrorCode.ORDER_NOT_ADMISSIBLE);
            require(screening.status()>=0 && screening.status()<=4,ErrorCode.INTERNAL_ERROR);
            require(screening.admission()!=null && screening.start()!=null && screening.end()!=null
                    && !screening.admission().isAfter(screening.start()) && screening.end().isAfter(screening.start()),ErrorCode.INTERNAL_ERROR);
            require(screening.status()==ScreeningStatus.ON_SALE.getCode()
                    || screening.status()==ScreeningStatus.OFF_SALE.getCode(),ErrorCode.SCREENING_STATUS_CONFLICT);
            now=clock.instant();
            LocalDateTime local=LocalDateTime.ofInstant(now,ZoneId.of("Asia/Shanghai"));
            require(!local.isBefore(screening.admission()),ErrorCode.ADMISSION_NOT_OPEN);
            require(local.isBefore(screening.end()),ErrorCode.ADMISSION_ENDED);
            now=now.truncatedTo(ChronoUnit.SECONDS);
            require(mapper.use(current.ticketId(),now)==1,ErrorCode.INTERNAL_ERROR);
            try { mapper.audit(IdWorker.getId(),current.ticketId(),operator,now); }
            catch (DuplicateKeyException e) { throw new TicketVerificationException(ErrorCode.INTERNAL_ERROR,e); }
            var successful=mapper.byId(current.ticketId());
            TicketVerificationRules.validateSuccess(successful);
            TicketVerificationVO result=converter.success(successful);
            require(mapper.save(operator,OPERATION,key,json.writeValueAsString(result),now)==1,ErrorCode.INTERNAL_ERROR);
            return result;
        });
    }

    private TicketVerificationVO historical(TicketVerificationMapper.Idempotency row,String digest,Instant now) {
        if (row==null || !row.expiresAt().isAfter(now)) { return null; }
        require(row.digest().equals(digest),ErrorCode.IDEMPOTENCY_CONFLICT);
        require(row.response()!=null,ErrorCode.INTERNAL_ERROR);
        try {
            TicketVerificationVO result=json.readValue(row.response(),TicketVerificationVO.class);
            require(result!=null && result.verificationId()!=null && "USED".equals(result.status())
                    && result.verifiedAt()!=null,ErrorCode.INTERNAL_ERROR);
            return result;
        } catch (RuntimeException e) { throw new TicketVerificationException(ErrorCode.INTERNAL_ERROR); }
    }

    public int cleanup() {
        return transaction.execute(status -> mapper.cleanup(OPERATION,clock.instant(),500));
    }

    private static void require(boolean valid,ErrorCode code) {
        if (!valid) { throw new TicketVerificationException(code); }
    }
}
