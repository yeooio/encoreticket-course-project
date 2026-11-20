package io.github.chenforegone.encoreticket.service.transaction;

import io.github.chenforegone.encoreticket.common.exception.TicketVerificationException;

import io.github.chenforegone.encoreticket.converter.TicketVerificationConverter;
import io.github.chenforegone.encoreticket.mapper.TicketVerificationMapper;
import io.github.chenforegone.encoreticket.model.query.TicketVerificationProjection;
import io.github.chenforegone.encoreticket.common.error.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.dao.DuplicateKeyException;
import tools.jackson.databind.json.JsonMapper;
import java.time.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/**
 * 验证事务回滚、锁序、时间边界及历史快照。
 *
 * @author c1342
 */
class TicketVerificationTransactionTest {
    private final TicketVerificationMapper mapper=mock(TicketVerificationMapper.class);
    private final PlatformTransactionManager manager=mock(PlatformTransactionManager.class);
    private final TransactionStatus status=mock(TransactionStatus.class);
    private final Instant now=Instant.parse("2026-10-19T12:00:00Z");
    private final LocalDateTime local=LocalDateTime.of(2026,10,19,20,0);
    private TicketVerificationTransaction transaction;

    @BeforeEach
    void setup() {
        when(manager.getTransaction(any())).thenReturn(status);
        transaction=new TicketVerificationTransaction(mapper,new TicketVerificationConverter(),()->now,JsonMapper.builder().build(),manager);
        when(mapper.operatorExists(7)).thenReturn(true);
        when(mapper.lockScreening(3)).thenReturn(new TicketVerificationMapper.Screening(3,2,local,local.plusMinutes(15),local.plusHours(2)));
        when(mapper.byCode("Code")).thenReturn(projection(false));
        when(mapper.byId(1)).thenReturn(projection(false),projection(true));
        when(mapper.lockOrder(2)).thenReturn(1);
        when(mapper.lockTicket(1)).thenReturn(0);
        when(mapper.use(1,now)).thenReturn(1);
        when(mapper.save(eq(7L),anyString(),anyString(),anyString(),eq(now))).thenReturn(1);
    }

    @Test
    void commitsAtOpeningWithScreeningOrderTicketLockOrder() {
        var result=transaction.execute(7,"Key","digest","Code",3);
        assertEquals("USED",result.status());
        assertEquals("+08:00",result.startTime().getOffset().toString());
        var order=inOrder(mapper,manager);
        order.verify(mapper).lockScreening(3);
        order.verify(mapper).lockOrder(2);
        order.verify(mapper).lockTicket(1);
        order.verify(mapper).use(1,now);
        order.verify(mapper).audit(anyLong(),eq(1L),eq(7L),eq(now));
        order.verify(mapper).save(eq(7L),anyString(),anyString(),anyString(),eq(now));
        order.verify(manager).commit(status);
        verify(manager).getTransaction(argThat(d -> d.getPropagationBehavior()==TransactionDefinition.PROPAGATION_REQUIRES_NEW));
    }

    @Test
    void auditUniqueFailureIsInternalAndRollsBack() {
        doThrow(new DuplicateKeyException("sensitive SQL Code")).when(mapper).audit(anyLong(),anyLong(),anyLong(),any());
        assertEquals(ErrorCode.INTERNAL_ERROR,assertThrows(TicketVerificationException.class,
                ()->transaction.execute(7,"Key","digest","Code",3)).getErrorCode());
        verify(manager).rollback(status);
        verify(mapper,never()).save(anyLong(),anyString(),anyString(),anyString(),any());
    }

    @Test
    void serviceAuditDuplicateRetainsDatabaseDiagnosticAndStaysInternal() {
        doThrow(new DuplicateKeyException("password=secret INSERT ticket SecretCode",
                new java.sql.SQLException("secret SQL", "23000", 1062)))
                .when(mapper).audit(anyLong(),anyLong(),anyLong(),any());
        var service=new io.github.chenforegone.encoreticket.service.impl.TicketVerificationServiceImpl(
                transaction,mapper,new TicketVerificationConverter());
        var request=JsonMapper.builder().build().readValue(
                "{\"ticketCode\":\"Code\",\"screeningId\":\"3\"}",
                io.github.chenforegone.encoreticket.model.dto.TicketVerificationRequest.class);
        var logger=(ch.qos.logback.classic.Logger)org.slf4j.LoggerFactory.getLogger(service.getClass());
        var appender=new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        appender.start(); logger.addAppender(appender);
        try {
            assertEquals(ErrorCode.INTERNAL_ERROR,assertThrows(TicketVerificationException.class,
                    ()->service.verify(7,"Key",request)).getErrorCode());
            assertEquals(1,appender.list.size());
            String diagnostic=appender.list.getFirst().getFormattedMessage();
            assertTrue(diagnostic.contains("DuplicateKeyException"));
            assertTrue(diagnostic.contains("sqlState=23000, vendorCode=1062"));
            assertFalse(diagnostic.contains("password=secret"));
            assertFalse(diagnostic.contains("INSERT ticket"));
            assertFalse(diagnostic.contains("SecretCode"));
            assertFalse(diagnostic.contains("secret SQL"));
            assertNull(appender.list.getFirst().getThrowableProxy());
        } finally { logger.detachAppender(appender); appender.stop(); }
    }

    @Test
    void reservationCollisionRollsBackBeforeCallerCanReplay() {
        doThrow(new DuplicateKeyException("key")).when(mapper).reserve(anyLong(),anyString(),anyString(),anyString(),any());
        assertThrows(TicketVerificationTransaction.ReservationCollision.class,()->transaction.execute(7,"Key","digest","Code",3));
        verify(manager).rollback(status);
        verify(mapper,never()).lockScreening(anyLong());
    }

    @Test
    void replayAfterEndUsesSnapshotWithoutBusinessLocks() {
        String json=JsonMapper.builder().build().writeValueAsString(new TicketVerificationConverter().success(projection(true)));
        when(mapper.find(7,TicketVerificationTransaction.OPERATION,"Key",true))
                .thenReturn(new TicketVerificationMapper.Idempotency("digest",json,now.plusSeconds(100)));
        assertEquals(new TicketVerificationConverter().success(projection(true)),transaction.replay(7,"Key","digest"));
        verify(mapper,never()).lockScreening(anyLong());
        assertEquals(ErrorCode.IDEMPOTENCY_CONFLICT,assertThrows(TicketVerificationException.class,
                ()->transaction.replay(7,"Key","different")).getErrorCode());
    }

    @ParameterizedTest
    @ValueSource(strings={"UTC","America/New_York"})
    void snapshotAndBothReplayPathsPreserveShanghaiOffsetAndUtcVerification(String contextZone) {
        var json=JsonMapper.builder().defaultTimeZone(java.util.TimeZone.getTimeZone(contextZone)).build();
        transaction=new TicketVerificationTransaction(mapper,new TicketVerificationConverter(),()->now,json,manager);
        var first=transaction.execute(7,"Key","digest","Code",3);
        var snapshot=ArgumentCaptor.forClass(String.class);
        verify(mapper).save(eq(7L),eq(TicketVerificationTransaction.OPERATION),eq("Key"),snapshot.capture(),eq(now));
        var tree=json.readTree(snapshot.getValue());
        assertEquals("2026-10-19T20:00:00+08:00",tree.get("startTime").asText());
        assertEquals("2026-10-19T22:00:00+08:00",tree.get("endTime").asText());
        assertEquals("2026-10-19T12:00:00Z",tree.get("verifiedAt").asText());
        var row=new TicketVerificationMapper.Idempotency("digest",snapshot.getValue(),now.plusSeconds(86400));
        when(mapper.find(7,TicketVerificationTransaction.OPERATION,"Key",false)).thenReturn(row);
        when(mapper.find(7,TicketVerificationTransaction.OPERATION,"Key",true)).thenReturn(row);
        clearInvocations(mapper);
        var repeated=transaction.execute(7,"Key","digest","Code",3);
        var collisionReplay=transaction.replay(7,"Key","digest");
        for (var result:java.util.List.of(first,repeated,collisionReplay)) {
            assertEquals(local.atOffset(ZoneOffset.ofHours(8)),result.startTime());
            assertEquals(local.plusHours(2).atOffset(ZoneOffset.ofHours(8)),result.endTime());
            assertEquals(now,result.verifiedAt());
            assertEquals(first,result);
            assertEquals(json.writeValueAsString(first),json.writeValueAsString(result));
        }
        verify(mapper,never()).lockScreening(anyLong());
        verify(mapper,never()).reserve(anyLong(),anyString(),anyString(),anyString(),any());
        verify(mapper,never()).use(anyLong(),any());
        verify(mapper,never()).audit(anyLong(),anyLong(),anyLong(),any());
        verify(mapper,never()).save(anyLong(),anyString(),anyString(),anyString(),any());
        // 派生快照配置不能改变注入的共享 mapper。
        var adjusted=json.readValue(snapshot.getValue(),io.github.chenforegone.encoreticket.model.vo.TicketVerificationVO.class);
        assertEquals(first.startTime().toInstant().atZone(ZoneId.of(contextZone)).toOffsetDateTime(),adjusted.startTime());
    }

    @Test
    void beforeOpeningAndExactEndAreRejectedAndRefundingCannotEnter() {
        when(mapper.lockScreening(3)).thenReturn(new TicketVerificationMapper.Screening(3,1,local.plusSeconds(1),local.plusMinutes(15),local.plusHours(2)));
        assertError(ErrorCode.ADMISSION_NOT_OPEN);
        when(mapper.byId(1)).thenReturn(projection(false));
        when(mapper.lockScreening(3)).thenReturn(new TicketVerificationMapper.Screening(3,1,local.minusHours(2),local.minusHours(1),local));
        assertError(ErrorCode.ADMISSION_ENDED);
        when(mapper.lockOrder(2)).thenReturn(4);
        when(mapper.byId(1)).thenReturn(new TicketVerificationProjection(1L,"Code",0,4L,2L,3L,4,"ORDER",5L,3L,2L,2,6L,8L,1,2,3L,10L,8L,"电影","厅",null,null,null,null,local,local.plusHours(2)));
        assertError(ErrorCode.ORDER_NOT_ADMISSIBLE);
    }

    @Test
    void missingRelationsAndInvalidStateAreInternalAndUsedIsSafe() {
        when(mapper.byCode("Code")).thenReturn(null);
        assertError(ErrorCode.RESOURCE_NOT_FOUND);
        when(mapper.byCode("Code")).thenReturn(projection(false));
        when(mapper.byId(1)).thenReturn(null);
        assertError(ErrorCode.INTERNAL_ERROR);
        when(mapper.byId(1)).thenReturn(projection(true));
        when(mapper.lockTicket(1)).thenReturn(1);
        assertError(ErrorCode.TICKET_ALREADY_USED);
    }

    @Test
    void closedAndWaitSaleAreRejectedAndLateEntryAllowed() {
        when(mapper.lockScreening(3)).thenReturn(new TicketVerificationMapper.Screening(3,0,local.minusHours(1),local,local.plusHours(1)));
        assertError(ErrorCode.SCREENING_STATUS_CONFLICT);
        when(mapper.byId(1)).thenReturn(projection(false));
        when(mapper.lockScreening(3)).thenReturn(new TicketVerificationMapper.Screening(3,3,local.minusHours(1),local,local.plusHours(1)));
        assertError(ErrorCode.SCREENING_STATUS_CONFLICT);
        when(mapper.byId(1)).thenReturn(projection(false),projection(true));
        when(mapper.lockScreening(3)).thenReturn(new TicketVerificationMapper.Screening(3,2,local.minusHours(1),local.minusMinutes(30),local.plusHours(1)));
        assertEquals("USED",transaction.execute(7,"Key","digest","Code",3).status());
    }

    @Test
    void crossMidnightAdmissionUsesShanghaiWallTime() {
        Instant midnight=Instant.parse("2026-10-19T16:05:00Z");
        transaction=new TicketVerificationTransaction(mapper,new TicketVerificationConverter(),()->midnight,JsonMapper.builder().build(),manager);
        when(mapper.lockScreening(3)).thenReturn(new TicketVerificationMapper.Screening(3,2,
                LocalDateTime.of(2026,10,19,23,30),LocalDateTime.of(2026,10,19,23,45),LocalDateTime.of(2026,10,20,1,30)));
        when(mapper.use(1,midnight)).thenReturn(1);
        when(mapper.save(eq(7L),anyString(),anyString(),anyString(),eq(midnight))).thenReturn(1);
        assertEquals("USED",transaction.execute(7,"Key","digest","Code",3).status());
    }

    private void assertError(ErrorCode code) {
        assertEquals(code,assertThrows(TicketVerificationException.class,()->transaction.execute(7,"Key","digest","Code",3)).getErrorCode());
    }

    private TicketVerificationProjection projection(boolean used) {
        return new TicketVerificationProjection(1L,"Code",used?1:0,4L,2L,3L,1,"ORDER",5L,3L,2L,2,
                6L,8L,1,2,3L,10L,8L,"电影","厅",used?9L:null,used?7L:null,used?"DEFAULT":null,used?now:null,local,local.plusHours(2));
    }
}
