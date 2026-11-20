package io.github.chenforegone.encoreticket.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.github.chenforegone.encoreticket.common.exception.ConflictException;
import io.github.chenforegone.encoreticket.common.exception.ResourceNotFoundException;
import io.github.chenforegone.encoreticket.converter.MovieConverter;
import io.github.chenforegone.encoreticket.mapper.MovieMapper;
import io.github.chenforegone.encoreticket.model.dto.MovieQueryRequest;
import io.github.chenforegone.encoreticket.model.dto.OfflineMovieRequest;
import io.github.chenforegone.encoreticket.model.entity.Movie;
import io.github.chenforegone.encoreticket.model.enums.MovieStatus;
import io.github.chenforegone.encoreticket.service.impl.MovieServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * 电影业务规则测试。
 *
 * @author c1342
 */
class MovieServiceImplTest {

    private MovieMapper movieMapper;
    private MovieServiceImpl service;

    @BeforeEach
    void setUp() {
        movieMapper = mock(MovieMapper.class);
        service = new MovieServiceImpl(movieMapper, new MovieConverter());
    }

    @Test
    void publicDetailHidesOfflineMovieAsNotFound() {
        Movie movie = movie(1L, MovieStatus.OFFLINE);
        when(movieMapper.selectById(1L)).thenReturn(movie);

        assertThatThrownBy(() -> service.getPublic("1"))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void repeatedOfflineReturnsConflict() {
        Movie movie = movie(1L, MovieStatus.OFFLINE);
        when(movieMapper.selectById(1L)).thenReturn(movie);

        assertThatThrownBy(() -> service.offline("1", new OfflineMovieRequest(0)))
                .isInstanceOf(ConflictException.class);
        verify(movieMapper, never()).updateById(any(Movie.class));
    }

    @Test
    void publicListAlwaysQueriesOnlyOnlineMoviesAndKeepsDateFilters() {
        when(movieMapper.selectMoviePage(any(Page.class), eq(MovieStatus.ONLINE), eq("流浪"),
                eq(LocalDate.of(2026, 10, 1)), eq(LocalDate.of(2026, 10, 31)),
                isNull(), isNull(), eq("releaseDate"), eq("asc")))
                .thenReturn(new Page<>());

        service.listPublic(new MovieQueryRequest(1, 20, "流浪", null,
                LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31),
                null, null, "releaseDate", "asc"));

        verify(movieMapper).selectMoviePage(any(Page.class), eq(MovieStatus.ONLINE), eq("流浪"),
                eq(LocalDate.of(2026, 10, 1)), eq(LocalDate.of(2026, 10, 31)),
                isNull(), isNull(), eq("releaseDate"), eq("asc"));
    }

    private static Movie movie(Long id, MovieStatus status) {
        Movie movie = new Movie();
        movie.setId(id);
        movie.setStatus(status);
        movie.setVersion(0);
        movie.setTitle("示例电影");
        movie.setReleaseDate(LocalDate.of(2026, 10, 1));
        return movie;
    }
}
