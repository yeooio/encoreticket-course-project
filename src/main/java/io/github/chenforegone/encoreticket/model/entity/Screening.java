package io.github.chenforegone.encoreticket.model.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.github.chenforegone.encoreticket.model.enums.ScreeningStatus;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;

/**
 * 电影放映场次实体，对应 {@code screening} 表。
 *
 * @author c1342
 */
@Getter
@Setter
@TableName("screening")
public class Screening {

    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private Long id;
    private BigDecimal price;
    private Long movieId;
    private Long hallId;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private Integer durationMinutesSnapshot;
    private Integer turnaroundMinutesSnapshot;
    private LocalDateTime saleStartTime;
    private LocalDateTime saleEndTime;
    private LocalDateTime admissionOpenTime;
    private ScreeningStatus status;
    private Integer version;

    @TableField(value = "create_time", fill = FieldFill.INSERT)
    private Instant createTime;

    @TableField(value = "update_time", fill = FieldFill.INSERT_UPDATE)
    private Instant updateTime;
}
