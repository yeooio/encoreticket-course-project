package io.github.chenforegone.encoreticket.converter;

import io.github.chenforegone.encoreticket.model.entity.Screening;
import io.github.chenforegone.encoreticket.model.enums.HallStatus;
import io.github.chenforegone.encoreticket.model.enums.MovieStatus;
import io.github.chenforegone.encoreticket.model.enums.ScreeningStatus;
import io.github.chenforegone.encoreticket.model.query.ScreeningProjection;
import io.github.chenforegone.encoreticket.model.query.ScreeningSeatProjection;
import io.github.chenforegone.encoreticket.model.vo.AdminScreeningDetailVO;
import io.github.chenforegone.encoreticket.model.vo.AdminScreeningSummaryVO;
import io.github.chenforegone.encoreticket.model.vo.ScreeningDetailVO;
import io.github.chenforegone.encoreticket.model.vo.ScreeningSeatVO;
import io.github.chenforegone.encoreticket.model.vo.ScreeningSummaryVO;
import org.springframework.stereotype.Component;

import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Objects;

/**
 * 场次投影转换，统一金额、字符串 ID 与影院时区。
 *
 * @author c1342
 */
@Component
public class ScreeningConverter {
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");

    public ScreeningSummaryVO toSummaryVO(ScreeningProjection s, LocalDateTime now) {
        return new ScreeningSummaryVO(id(s.getId()), id(s.getMovieId()), id(s.getHallId()),
                s.getMovieTitle(), s.getHallName(), price(s), "CNY",
                toOffset(s.getStartTime()), toOffset(s.getEndTime()),
                toOffset(s.getSaleStartTime()), toOffset(s.getSaleEndTime()),
                s.getStatus().name(), canPurchase(s, now));
    }

    public ScreeningDetailVO toDetailVO(ScreeningProjection s, LocalDateTime now) {
        return new ScreeningDetailVO(id(s.getId()), id(s.getMovieId()), id(s.getHallId()),
                s.getMovieTitle(), s.getHallName(), price(s), "CNY",
                toOffset(s.getStartTime()), toOffset(s.getEndTime()),
                toOffset(s.getSaleStartTime()), toOffset(s.getSaleEndTime()),
                s.getStatus().name(), canPurchase(s, now),
                s.getDurationMinutesSnapshot(), s.getTurnaroundMinutesSnapshot());
    }

    public AdminScreeningSummaryVO toAdminSummaryVO(ScreeningProjection s) {
        return new AdminScreeningSummaryVO(id(s.getId()), id(s.getMovieId()), id(s.getHallId()),
                s.getMovieTitle(), s.getHallName(), price(s), "CNY",
                toOffset(s.getStartTime()), toOffset(s.getEndTime()),
                toOffset(s.getSaleStartTime()), toOffset(s.getSaleEndTime()),
                s.getStatus().name(), s.getVersion());
    }

    public AdminScreeningDetailVO toAdminDetailVO(ScreeningProjection s) {
        return new AdminScreeningDetailVO(id(s.getId()), id(s.getMovieId()), id(s.getHallId()),
                s.getMovieTitle(), s.getHallName(), price(s), "CNY",
                toOffset(s.getStartTime()), toOffset(s.getEndTime()),
                toOffset(s.getSaleStartTime()), toOffset(s.getSaleEndTime()),
                s.getStatus().name(), s.getVersion(),
                s.getDurationMinutesSnapshot(), s.getTurnaroundMinutesSnapshot(),
                s.getCreateTime(), s.getUpdateTime(), toOffset(s.getAdmissionOpenTime()));
    }

    public ScreeningSeatVO toSeatVO(ScreeningSeatProjection seat) {
        return new ScreeningSeatVO(id(seat.getId()), id(seat.getSeatId()),
                seat.getRowNumber(), seat.getSeatNumber(), seat.getStatus().getCode());
    }

    public boolean canPurchase(ScreeningProjection s, LocalDateTime now) {
        return s.getStatus() == ScreeningStatus.ON_SALE
                && s.getMovieStatus() == MovieStatus.ONLINE && s.getHallStatus() == HallStatus.ENABLED
                && !now.isBefore(s.getSaleStartTime()) && now.isBefore(s.getSaleEndTime());
    }

    public OffsetDateTime toOffset(LocalDateTime value) {
        return value == null ? null : value.atZone(BUSINESS_ZONE).toOffsetDateTime();
    }

    private String id(Long value) {
        return Objects.toString(value, null);
    }

    private String price(Screening value) {
        return value.getPrice() == null ? null : value.getPrice().setScale(2, RoundingMode.UNNECESSARY).toPlainString();
    }
}
