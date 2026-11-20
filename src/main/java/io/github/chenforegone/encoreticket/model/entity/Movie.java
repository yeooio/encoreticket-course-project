package io.github.chenforegone.encoreticket.model.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import io.github.chenforegone.encoreticket.model.enums.MovieStatus;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import java.time.Instant;
import java.time.LocalDate;

/**
 * 电影实体，对应 {@code movie} 表。
 *
 * @author c1342
 */
@Getter
@Setter
@ToString
@TableName("movie")
public class Movie {

    /** 电影主键。 */
    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private Long id;

    /** 电影名称。 */
    private String title;

    /** 电影简介。 */
    private String description;

    /** 现实上映日期。 */
    private LocalDate releaseDate;

    /** 影片播放时长（分钟）；扩展迁移阶段允许历史记录尚未补录。 */
    private Integer durationMinutes;

    /** 系统自动发行的绝对时间，按 UTC 保存；下架时允许通过 ALWAYS 清空。 */
    @TableField(value = "publish_at", updateStrategy = FieldStrategy.ALWAYS)
    private Instant publishAt;

    /** 电影目录状态。 */
    private MovieStatus status;

    /** 乐观锁版本。 */
    @Version
    private Integer version;

    /** 创建时间，按 UTC 保存。 */
    @TableField(value = "create_time", fill = FieldFill.INSERT)
    private Instant createTime;

    /** 修改时间，按 UTC 保存。 */
    @TableField(value = "update_time", fill = FieldFill.INSERT_UPDATE)
    private Instant updateTime;
}
