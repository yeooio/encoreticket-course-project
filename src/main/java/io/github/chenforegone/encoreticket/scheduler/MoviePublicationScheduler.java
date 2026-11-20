package io.github.chenforegone.encoreticket.scheduler;

import io.github.chenforegone.encoreticket.mapper.MovieMapper;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

import java.time.Instant;

/**
 * 自动发行到达发行时间的电影。
 *
 * @author c1342
 */
@Component
@ConditionalOnProperty(
        prefix = "encore.movie.publication-scheduler",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true
)
public class MoviePublicationScheduler {

    private static final int BATCH_SIZE = 100;

    private final MovieMapper movieMapper;

    public MoviePublicationScheduler(MovieMapper movieMapper) {
        this.movieMapper = movieMapper;
    }

    /** 每分钟执行一次；条件更新保证多实例重复触发时也只会成功一次。 */
    @Scheduled(fixedDelay = 60000)
    public void publishDueMovies() {
        movieMapper.publishDueMovies(Instant.now(), BATCH_SIZE);
    }
}
