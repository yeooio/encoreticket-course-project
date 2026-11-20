package io.github.chenforegone.encoreticket.model.query;

import io.github.chenforegone.encoreticket.model.entity.Screening;
import io.github.chenforegone.encoreticket.model.enums.HallStatus;
import io.github.chenforegone.encoreticket.model.enums.MovieStatus;
import lombok.Getter;
import lombok.Setter;

/**
 * 场次与电影、影厅的关联查询投影，不直接返回 HTTP。
 *
 * @author c1342
 */
@Getter
@Setter
public class ScreeningProjection extends Screening {
    private String movieTitle;
    private String hallName;
    private MovieStatus movieStatus;
    private HallStatus hallStatus;
}

