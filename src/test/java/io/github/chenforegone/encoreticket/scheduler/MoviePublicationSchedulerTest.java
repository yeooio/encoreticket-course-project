package io.github.chenforegone.encoreticket.scheduler;

import io.github.chenforegone.encoreticket.mapper.MovieMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * 电影自动发行任务测试。
 *
 * @author c1342
 */
class MoviePublicationSchedulerTest {

    @Test
    void publishesDueMoviesInBoundedBatch() {
        MovieMapper mapper = mock(MovieMapper.class);
        MoviePublicationScheduler scheduler = new MoviePublicationScheduler(mapper);

        scheduler.publishDueMovies();

        verify(mapper).publishDueMovies(any(Instant.class), eq(100));
    }
}
