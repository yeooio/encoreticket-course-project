package io.github.chenforegone.encoreticket.mapper;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.github.chenforegone.encoreticket.model.entity.Movie;
import io.github.chenforegone.encoreticket.model.enums.MovieStatus;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDate;
import java.time.Instant;

/**
 * 电影数据访问接口。
 *
 * @author c1342
 */
@Mapper
public interface MovieMapper extends BaseMapper<Movie> {

    IPage<Movie> selectMoviePage(
            Page<Movie> page,
            @Param("status") MovieStatus status,
            @Param("title") String title,
            @Param("releaseDateFrom") LocalDate releaseDateFrom,
            @Param("releaseDateTo") LocalDate releaseDateTo,
            @Param("publishAtFrom") Instant publishAtFrom,
            @Param("publishAtTo") Instant publishAtTo,
            @Param("sortBy") String sortBy,
            @Param("sortOrder") String sortOrder
    );

    int publishDueMovies(@Param("now") Instant now, @Param("limit") int limit);

    int claimForScheduling(@Param("id") long id, @Param("version") int version);

    long countScreeningsBeforeRelease(@Param("id") long id, @Param("releaseDate") LocalDate releaseDate);
}
