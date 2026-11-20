package io.github.chenforegone.encoreticket.converter;

import io.github.chenforegone.encoreticket.model.query.TicketVerificationProjection;
import io.github.chenforegone.encoreticket.model.vo.TicketVerificationVO;
import io.github.chenforegone.encoreticket.model.vo.TicketVerificationAdminVO;
import org.springframework.stereotype.Component;
import java.time.ZoneId;

/**
 * 将已通过业务校验的投影转换为接口结果，统一 ID、状态和时区格式。
 *
 * @author c1342
 */
@Component
public class TicketVerificationConverter {
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    public TicketVerificationVO success(TicketVerificationProjection p) {
        return new TicketVerificationVO(p.verificationId().toString(), p.ticketId().toString(),
                p.screeningId().toString(), p.movieTitle(), p.startTime().atZone(ZONE).toOffsetDateTime(),
                p.endTime().atZone(ZONE).toOffsetDateTime(), p.hallName(), p.rowNumber(), p.seatNumber(), "USED", p.verifiedAt());
    }

    public TicketVerificationAdminVO admin(TicketVerificationProjection p) {
        TicketVerificationVO v = success(p);
        return new TicketVerificationAdminVO(v.verificationId(), v.ticketId(), v.screeningId(), v.movieTitle(),
                v.startTime(), v.endTime(), v.hallName(), v.rowNumber(), v.seatNumber(), v.status(), v.verifiedAt(),
                p.orderNo(), p.operatorId().toString(), p.deviceCode());
    }
}
