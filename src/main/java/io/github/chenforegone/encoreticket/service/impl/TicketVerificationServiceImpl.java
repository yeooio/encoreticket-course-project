package io.github.chenforegone.encoreticket.service.impl;

import io.github.chenforegone.encoreticket.common.error.ErrorCode;
import io.github.chenforegone.encoreticket.common.response.PageResponse;
import io.github.chenforegone.encoreticket.converter.TicketVerificationConverter;
import io.github.chenforegone.encoreticket.mapper.TicketVerificationMapper;
import io.github.chenforegone.encoreticket.model.dto.TicketVerificationRequest;
import io.github.chenforegone.encoreticket.model.dto.TicketVerificationQueryRequest;
import io.github.chenforegone.encoreticket.model.vo.TicketVerificationVO;
import io.github.chenforegone.encoreticket.model.vo.TicketVerificationAdminVO;
import io.github.chenforegone.encoreticket.common.exception.TicketVerificationException;
import io.github.chenforegone.encoreticket.service.TicketVerificationService;
import io.github.chenforegone.encoreticket.service.transaction.TicketVerificationTransaction;
import io.github.chenforegone.encoreticket.service.rules.TicketVerificationRules;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * 核销编排，确保唯一键竞争事务结束后才重放。
 *
 * @author c1342
 */
@Service
public class TicketVerificationServiceImpl implements TicketVerificationService {
    private static final Logger log=LoggerFactory.getLogger(TicketVerificationServiceImpl.class);
    private final TicketVerificationTransaction transaction;
    private final TicketVerificationMapper mapper;
    private final TicketVerificationConverter converter;

    public TicketVerificationServiceImpl(TicketVerificationTransaction transaction,TicketVerificationMapper mapper,
            TicketVerificationConverter converter) { this.transaction=transaction; this.mapper=mapper; this.converter=converter; }

    @Override
    @PreAuthorize("hasRole('CHECKIN_STAFF')")
    public TicketVerificationVO verify(long operator,String key,TicketVerificationRequest request) {
        try {
            if (operator<=0 || key==null || !key.matches("[A-Za-z0-9_-]{1,128}") || request==null) {
                throw new TicketVerificationException(ErrorCode.INVALID_ARGUMENT);
            }
            long screening=request.validatedScreeningId();
            String digest=digest(request.getTicketCode(),screening);
            TicketVerificationVO replay=transaction.replay(operator,key,digest);
            if (replay!=null) { return replay; }
            try { return transaction.execute(operator,key,digest,request.getTicketCode(),screening); }
            catch (TicketVerificationTransaction.ReservationCollision e) {
                replay=transaction.replay(operator,key,digest);
                if (replay!=null) { return replay; }
                // 清理可能恰好移走已到期记录；以同样原子流程重新争抢一次。
                return transaction.execute(operator,key,digest,request.getTicketCode(),screening);
            }
        } catch (RuntimeException e) {
            String code=e instanceof TicketVerificationException failure?failure.getErrorCode().getCode():"INTERNAL_ERROR";
            var attributes=org.springframework.web.context.request.RequestContextHolder.getRequestAttributes();
            Object requestId=attributes==null?null:attributes.getAttribute(
                    io.github.chenforegone.encoreticket.common.constant.RequestIdConstant.ATTRIBUTE_NAME,
                    org.springframework.web.context.request.RequestAttributes.SCOPE_REQUEST);
            if (e instanceof org.springframework.dao.DataAccessException
                    || e instanceof TicketVerificationTransaction.ReservationCollision
                    || e instanceof TicketVerificationException failure
                    && failure.getErrorCode()==ErrorCode.INTERNAL_ERROR && failure.getCause()!=null) {
                if (attributes instanceof org.springframework.web.context.request.ServletRequestAttributes servlet) {
                    io.github.chenforegone.encoreticket.common.exception.ExceptionLogSupport.logOnce(
                            log,servlet.getRequest(),"核销数据库失败 code=INTERNAL_ERROR",e);
                } else {
                    log.error("核销数据库失败 code=INTERNAL_ERROR{}",
                            io.github.chenforegone.encoreticket.common.exception.ExceptionLogSupport.safeStackTrace(e));
                }
                throw new TicketVerificationException(ErrorCode.INTERNAL_ERROR);
            }
            log.warn("核销失败 requestId={} code={} exceptionType={}",requestId,code,e.getClass().getSimpleName());
            throw e;
        }
    }

    @Override
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional(readOnly=true)
    public PageResponse<TicketVerificationAdminVO> list(TicketVerificationQueryRequest request) {
        request.validate();
        long total=mapper.count(request);
        return new PageResponse<>(mapper.list(request).stream().map(projection -> {
            TicketVerificationRules.validateSuccess(projection);
            return converter.admin(projection);
        }).toList(),
                request.pageNumber(),request.size(),total);
    }

    private static String digest(String code,long screening) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest((screening+":"+code).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
