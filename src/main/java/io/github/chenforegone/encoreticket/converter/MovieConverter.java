package io.github.chenforegone.encoreticket.converter;

import io.github.chenforegone.encoreticket.model.entity.Movie;
import io.github.chenforegone.encoreticket.model.enums.MovieStatus;
import io.github.chenforegone.encoreticket.model.vo.AdminMovieDetailVO;
import io.github.chenforegone.encoreticket.model.vo.MovieDetailVO;
import io.github.chenforegone.encoreticket.model.vo.MovieSummaryVO;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Objects;

/**
 * 电影 Entity、DTO 和 VO 转换器。
 *
 * @author c1342
 */
@Component
public class MovieConverter {

    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");

    public MovieSummaryVO toSummaryVO(Movie movie) {
        if (movie == null) {
            return null;
        }
        return new MovieSummaryVO(Objects.toString(movie.getId(), null), movie.getTitle(), movie.getDescription(),
                movie.getReleaseDate(), movie.getDurationMinutes(), status(movie));
    }

    public MovieDetailVO toDetailVO(Movie movie) {
        if (movie == null) {
            return null;
        }
        return new MovieDetailVO(Objects.toString(movie.getId(), null), movie.getTitle(), movie.getDescription(),
                movie.getReleaseDate(), movie.getDurationMinutes(), status(movie));
    }

    public AdminMovieDetailVO toAdminDetailVO(Movie movie) {
        if (movie == null) {
            return null;
        }
        OffsetDateTime publishAt = toBusinessOffset(movie.getPublishAt());
        return new AdminMovieDetailVO(Objects.toString(movie.getId(), null), movie.getTitle(), movie.getDescription(),
                movie.getReleaseDate(), movie.getDurationMinutes(), publishAt, status(movie), movie.getVersion(),
                movie.getCreateTime(), movie.getUpdateTime());
    }

    private static OffsetDateTime toBusinessOffset(Instant value) {
        return value == null ? null : value.atZone(BUSINESS_ZONE).toOffsetDateTime();
    }

    private static String status(Movie movie) {
        MovieStatus status = movie.getStatus();
        return status == null ? null : status.getCode();
    }
}
