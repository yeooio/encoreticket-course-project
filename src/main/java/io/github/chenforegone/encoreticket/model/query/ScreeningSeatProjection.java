package io.github.chenforegone.encoreticket.model.query;

import io.github.chenforegone.encoreticket.model.enums.ScreeningSeatStatus;
import lombok.Getter;
import lombok.Setter;

/**
 * 只读取展示座位所需列，不读取其他用户的订单数据。
 *
 * @author c1342
 */
@Getter
@Setter
public class ScreeningSeatProjection {
    private Long id;
    private Long seatId;
    private Integer rowNumber;
    private Integer seatNumber;
    private ScreeningSeatStatus status;
}

