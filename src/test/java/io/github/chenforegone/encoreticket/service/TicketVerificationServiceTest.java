package io.github.chenforegone.encoreticket.service;

import io.github.chenforegone.encoreticket.service.transaction.TicketVerificationTransaction;

import io.github.chenforegone.encoreticket.common.exception.TicketVerificationException;

import io.github.chenforegone.encoreticket.mapper.TicketVerificationMapper;
import io.github.chenforegone.encoreticket.converter.TicketVerificationConverter;
import io.github.chenforegone.encoreticket.service.impl.TicketVerificationServiceImpl;
import io.github.chenforegone.encoreticket.model.dto.TicketVerificationRequest;
import io.github.chenforegone.encoreticket.model.vo.TicketVerificationVO;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.slf4j.LoggerFactory;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import java.time.Instant;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/**
 * 编排验证：竞争后重新调用独立事务及失败日志脱敏。
 *
 * @author c1342
 */
class TicketVerificationServiceTest {
    private final TicketVerificationTransaction tx=mock(TicketVerificationTransaction.class);
    private final TicketVerificationServiceImpl service=new TicketVerificationServiceImpl(tx,
            mock(TicketVerificationMapper.class),new TicketVerificationConverter());
    private TicketVerificationRequest request(String code,String screening) {
        return JsonMapper.builder().build().readValue("{\"ticketCode\":\""+code+"\",\"screeningId\":\""+screening+"\"}",TicketVerificationRequest.class);
    }

    @Test
    void collisionReplaysOnlyAfterFailedAttemptReturns() {
        var result=new TicketVerificationVO("9","1","3","电影",null,null,"厅",1,2,"USED",Instant.EPOCH);
        when(tx.replay(eq(7L),eq("Key"),anyString())).thenReturn(null,result);
        when(tx.execute(eq(7L),eq("Key"),anyString(),eq("SecretCode"),eq(3L)))
                .thenThrow(new TicketVerificationTransaction.ReservationCollision());
        assertEquals(result,service.verify(7,"Key",request("SecretCode","3")));
        var order=inOrder(tx);
        order.verify(tx).replay(eq(7L),eq("Key"),anyString());
        order.verify(tx).execute(eq(7L),eq("Key"),anyString(),eq("SecretCode"),eq(3L));
        order.verify(tx).replay(eq(7L),eq("Key"),anyString());
    }

    @Test
    void databaseFailureKeepsSafeDiagnosticOncePerRequest() {
        var mapper=mock(TicketVerificationMapper.class);
        var manager=mock(org.springframework.transaction.PlatformTransactionManager.class);
        when(manager.getTransaction(any())).thenReturn(mock(org.springframework.transaction.TransactionStatus.class));
        var databaseFailure=new org.springframework.dao.DataAccessResourceFailureException(
                "SecretCode SecretKey password=secret SELECT * FROM ticket",
                new java.sql.SQLException("Bearer secret SQL", "08001", 1045));
        when(mapper.operatorExists(7)).thenThrow(databaseFailure);
        var actualService=new TicketVerificationServiceImpl(new TicketVerificationTransaction(mapper,
                new TicketVerificationConverter(),()->Instant.EPOCH,JsonMapper.builder().build(),manager),
                mapper,new TicketVerificationConverter());
        var httpRequest=new org.springframework.mock.web.MockHttpServletRequest("POST","/ticket-verifications");
        httpRequest.setAttribute("requestId","req-verification-db");
        org.springframework.web.context.request.RequestContextHolder.setRequestAttributes(
                new org.springframework.web.context.request.ServletRequestAttributes(httpRequest));
        Logger logger=(Logger)LoggerFactory.getLogger(TicketVerificationServiceImpl.class);
        var appender=new ListAppender<ILoggingEvent>(); appender.start(); logger.addAppender(appender);
        try {
            for (int attempt=0;attempt<2;attempt++) {
                var failure=assertThrows(TicketVerificationException.class,
                        ()->actualService.verify(7,"SecretKey",request("SecretCode","3")));
                assertEquals(io.github.chenforegone.encoreticket.common.error.ErrorCode.INTERNAL_ERROR,failure.getErrorCode());
                assertEquals("服务器内部错误",failure.getMessage());
            }
            assertEquals(1,appender.list.size());
            String diagnostic=appender.list.getFirst().getFormattedMessage();
            assertTrue(diagnostic.contains("req-verification-db"));
            assertTrue(diagnostic.contains("method=POST"));
            assertTrue(diagnostic.contains("path=/ticket-verifications"));
            assertTrue(diagnostic.contains("DataAccessResourceFailureException"));
            assertTrue(diagnostic.contains("java.sql.SQLException"));
            assertTrue(diagnostic.contains("sqlState=08001"));
            assertTrue(diagnostic.contains("1045"));
            assertTrue(diagnostic.contains("at "));
            for (String secret:new String[]{"SecretCode","SecretKey","password=secret","SELECT *","Bearer secret"}) {
                assertFalse(diagnostic.contains(secret));
            }
            assertNull(appender.list.getFirst().getThrowableProxy());
        } finally {
            org.springframework.web.context.request.RequestContextHolder.resetRequestAttributes();
            logger.detachAppender(appender); appender.stop();
        }
    }

    @Test
    void exhaustedReservationCollisionKeepsSafeStackWithoutRequestContext() {
        var mapper=mock(TicketVerificationMapper.class);
        var manager=mock(org.springframework.transaction.PlatformTransactionManager.class);
        when(manager.getTransaction(any())).thenReturn(mock(org.springframework.transaction.TransactionStatus.class));
        when(mapper.operatorExists(7)).thenReturn(true);
        doThrow(new org.springframework.dao.DuplicateKeyException("SecretCode SecretKey password=secret SQL",
                new java.sql.SQLException("secret SQL", "23000", 1062)))
                .when(mapper).reserve(anyLong(),anyString(),anyString(),anyString(),any());
        var actualService=new TicketVerificationServiceImpl(new TicketVerificationTransaction(mapper,
                new TicketVerificationConverter(),()->Instant.EPOCH,JsonMapper.builder().build(),manager),
                mapper,new TicketVerificationConverter());
        Logger logger=(Logger)LoggerFactory.getLogger(TicketVerificationServiceImpl.class);
        var appender=new ListAppender<ILoggingEvent>(); appender.start(); logger.addAppender(appender);
        try {
            var failure=assertThrows(TicketVerificationException.class,
                    ()->actualService.verify(7,"SecretKey",request("SecretCode","3")));
            assertEquals(io.github.chenforegone.encoreticket.common.error.ErrorCode.INTERNAL_ERROR,failure.getErrorCode());
            assertEquals(1,appender.list.size());
            String diagnostic=appender.list.getFirst().getFormattedMessage();
            assertTrue(diagnostic.contains("ReservationCollision"));
            assertTrue(diagnostic.contains("DuplicateKeyException"));
            assertTrue(diagnostic.contains("sqlState=23000, vendorCode=1062"));
            assertFalse(diagnostic.contains("SecretCode"));
            assertFalse(diagnostic.contains("SecretKey"));
            assertFalse(diagnostic.contains("password=secret"));
            assertFalse(diagnostic.contains("secret SQL"));
            assertNull(appender.list.getFirst().getThrowableProxy());
        } finally { logger.detachAppender(appender); appender.stop(); }
    }

    @Test
    void strictValidationRejectsIdsKeysAndDoesNotLogSecrets() {
        Logger logger=(Logger)LoggerFactory.getLogger(TicketVerificationServiceImpl.class);
        var appender=new ListAppender<ILoggingEvent>(); appender.start(); logger.addAppender(appender);
        try {
            for(String id:new String[]{"01","0","-1","9223372036854775808"}) {
                assertThrows(TicketVerificationException.class,()->service.verify(7,"SecretKey",request("SecretCode",id)));
            }
            assertThrows(TicketVerificationException.class,()->service.verify(7,"bad key",request("SecretCode","3")));
            assertThrows(TicketVerificationException.class,()->service.verify(7,"SecretKey",request("x".repeat(129),"3")));
            assertThrows(TicketVerificationException.class,()->service.verify(7,"SecretKey",request("   ","3")));
            verifyNoInteractions(tx);
            when(tx.replay(anyLong(),anyString(),anyString())).thenThrow(new IllegalStateException("SecretCode SecretKey Bearer secret SQL"));
            assertThrows(IllegalStateException.class,()->service.verify(7,"SecretKey",request("SecretCode","3")));
            String logs=appender.list.stream().map(ILoggingEvent::getFormattedMessage).reduce("",String::concat);
            assertTrue(logs.contains("INTERNAL_ERROR"));
            assertFalse(logs.contains("SecretCode")); assertFalse(logs.contains("SecretKey")); assertFalse(logs.contains("Bearer"));
            assertTrue(appender.list.stream().allMatch(e->e.getThrowableProxy()==null));
        } finally { logger.detachAppender(appender); appender.stop(); }
    }
}
