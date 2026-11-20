package io.github.chenforegone.encoreticket.service.rules;

import io.github.chenforegone.encoreticket.common.exception.TicketVerificationException;

import io.github.chenforegone.encoreticket.common.error.ErrorCode;
import io.github.chenforegone.encoreticket.converter.TicketVerificationConverter;
import io.github.chenforegone.encoreticket.model.query.TicketVerificationProjection;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.time.LocalDateTime;
import static org.junit.jupiter.api.Assertions.*;

/**
 * 关联校验属于业务规则，转换器仅处理已校验投影。
 *
 * @author c1342
 */
class TicketVerificationRulesTest {
    @Test
    void rejectsMissingOrInconsistentRelations() {
        assertInternal(()->TicketVerificationRules.validate(null));
        assertInternal(()->TicketVerificationRules.validate(projection(99L,7L)));
        assertInternal(()->TicketVerificationRules.validateSuccess(projection(3L,null)));
    }

    @Test
    void validAuditCanBeConvertedAfterRulesCheck() {
        var projection=projection(3L,7L);
        assertDoesNotThrow(()->TicketVerificationRules.validateSuccess(projection));
        var result=new TicketVerificationConverter().success(projection);
        assertEquals("USED",result.status());
        assertEquals("+08:00",result.startTime().getOffset().toString());
        assertEquals("9",result.verificationId());
    }

    private TicketVerificationProjection projection(Long seatScreening,Long operator) {
        LocalDateTime start=LocalDateTime.of(2026,10,19,20,0);
        return new TicketVerificationProjection(1L,"Code",1,4L,2L,3L,1,"ORDER",5L,seatScreening,2L,2,
                6L,8L,1,2,3L,10L,8L,"电影","厅",9L,operator,"DEFAULT",Instant.EPOCH,start,start.plusHours(2));
    }

    private void assertInternal(org.junit.jupiter.api.function.Executable action) {
        assertEquals(ErrorCode.INTERNAL_ERROR,assertThrows(TicketVerificationException.class,action).getErrorCode());
    }
}
