package io.github.chenforegone.encoreticket.converter;

import io.github.chenforegone.encoreticket.model.entity.Movie;
import io.github.chenforegone.encoreticket.model.enums.MovieStatus;
import io.github.chenforegone.encoreticket.model.vo.AdminMovieDetailVO;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 电影模型转换测试。
 *
 * @author c1342
 */
class MovieConverterTest {

    private final MovieConverter converter = new MovieConverter();

    @Test
    void convertsUtcPublishTimeToBusinessOffsetAndStringId() {
        Movie movie = new Movie();
        movie.setId(10001L);
        movie.setTitle("示例电影");
        movie.setReleaseDate(LocalDate.of(2026, 10, 1));
        movie.setPublishAt(Instant.parse("2026-09-20T01:00:00Z"));
        movie.setStatus(MovieStatus.OFFLINE);
        movie.setVersion(3);

        AdminMovieDetailVO result = converter.toAdminDetailVO(movie);

        assertThat(result.id()).isEqualTo("10001");
        assertThat(result.status()).isEqualTo("OFFLINE");
        assertThat(result.publishAt()).hasToString("2026-09-20T09:00+08:00");
        assertThat(result.version()).isEqualTo(3);
    }
}
