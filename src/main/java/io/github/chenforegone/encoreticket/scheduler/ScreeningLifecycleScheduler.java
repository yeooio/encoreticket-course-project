package io.github.chenforegone.encoreticket.scheduler;

import io.github.chenforegone.encoreticket.mapper.ScreeningLifecycleMapper;
import io.github.chenforegone.encoreticket.mapper.ScreeningLifecycleMapper.Candidate;
import io.github.chenforegone.encoreticket.service.ScreeningService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * 按结束、停售、开售的顺序处理有界候选，每个场次由事务服务独立推进。
 *
 * @author c1342
 */
@Component
@ConditionalOnProperty(prefix = "encore.screening.lifecycle-scheduler", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class ScreeningLifecycleScheduler {

    private static final Logger LOG = LoggerFactory.getLogger(ScreeningLifecycleScheduler.class);
    private final ScreeningLifecycleMapper mapper;
    private final ScreeningService screeningService;
    private final Clock clock;
    private final int batchSize;
    private Candidate endedCursor;
    private Candidate offSaleCursor;
    private Candidate onSaleCursor;

    public ScreeningLifecycleScheduler(ScreeningLifecycleMapper mapper, ScreeningService screeningService,
            Clock clock, @Value("${encore.screening.lifecycle-scheduler.batch-size:100}") int batchSize) {
        if (batchSize < 1 || batchSize > 1000) {
            throw new IllegalArgumentException("Screening lifecycle batch size must be between 1 and 1000");
        }
        this.mapper = mapper;
        this.screeningService = screeningService;
        this.clock = clock;
        this.batchSize = batchSize;
    }

    /** 每阶段最多一批；失败记录下一轮扫回时重试，不阻塞其他候选或后续阶段。 */
    @Scheduled(fixedDelayString = "${encore.screening.lifecycle-scheduler.delay-ms:60000}")
    public synchronized void advanceDueScreenings() {
        LocalDateTime now = LocalDateTime.ofInstant(clock.instant(), ZoneId.of("Asia/Shanghai"))
                .truncatedTo(ChronoUnit.SECONDS);
        endedCursor = advancePhase("ended", mapper::selectEndedCandidates, endedCursor, now);
        offSaleCursor = advancePhase("off-sale", mapper::selectOffSaleCandidates, offSaleCursor, now);
        onSaleCursor = advancePhase("on-sale", mapper::selectOnSaleCandidates, onSaleCursor, now);
    }

    private Candidate advancePhase(String phase, CandidateQuery query, Candidate cursor, LocalDateTime now) {
        List<Candidate> candidates;
        try {
            candidates = query.select(now, cursor == null ? null : cursor.candidateTime(),
                    cursor == null ? null : cursor.id(), batchSize);
            if (candidates.isEmpty() && cursor != null) {
                candidates = query.select(now, null, null, batchSize);
            }
        } catch (RuntimeException exception) {
            LOG.warn("Screening lifecycle query failed: phase={}, failureType={}",
                    phase, exception.getClass().getName());
            return cursor;
        }
        for (Candidate candidate : candidates) {
            try {
                screeningService.advanceLifecycle(candidate.id());
            } catch (RuntimeException exception) {
                LOG.warn("Screening lifecycle transition failed: phase={}, screeningId={}, failureType={}",
                        phase, candidate.id(), exception.getClass().getName());
            }
        }
        return candidates.isEmpty() ? null : candidates.getLast();
    }

    /**
     * 同一阶段的游标查询签名。
     *
     * @author c1342
     */
    @FunctionalInterface
    private interface CandidateQuery {
        List<Candidate> select(LocalDateTime now, LocalDateTime afterTime, Long afterId, int limit);
    }
}
