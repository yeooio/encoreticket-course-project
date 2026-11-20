package io.github.chenforegone.encoreticket.service.impl;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import io.github.chenforegone.encoreticket.common.error.ErrorCode;
import io.github.chenforegone.encoreticket.common.exception.BusinessRuleException;
import io.github.chenforegone.encoreticket.common.exception.ConflictException;
import io.github.chenforegone.encoreticket.common.exception.InvalidRequestException;
import io.github.chenforegone.encoreticket.common.exception.ResourceNotFoundException;
import io.github.chenforegone.encoreticket.common.response.PageResponse;
import io.github.chenforegone.encoreticket.converter.ScreeningConverter;
import io.github.chenforegone.encoreticket.mapper.MovieMapper;
import io.github.chenforegone.encoreticket.mapper.ScreeningMapper;
import io.github.chenforegone.encoreticket.mapper.ScreeningQueryMapper;
import io.github.chenforegone.encoreticket.mapper.ScreeningSeatMapper;
import io.github.chenforegone.encoreticket.mapper.SeatMapper;
import io.github.chenforegone.encoreticket.model.dto.AdminScreeningQueryRequest;
import io.github.chenforegone.encoreticket.model.dto.CreateScreeningRequest;
import io.github.chenforegone.encoreticket.model.dto.PublicScreeningQueryRequest;
import io.github.chenforegone.encoreticket.model.dto.ScreeningVersionRequest;
import io.github.chenforegone.encoreticket.model.dto.UpdateScreeningRequest;
import io.github.chenforegone.encoreticket.model.entity.Movie;
import io.github.chenforegone.encoreticket.model.entity.Screening;
import io.github.chenforegone.encoreticket.model.entity.ScreeningSeat;
import io.github.chenforegone.encoreticket.model.entity.Seat;
import io.github.chenforegone.encoreticket.model.enums.MovieStatus;
import io.github.chenforegone.encoreticket.model.enums.ScreeningSaleMode;
import io.github.chenforegone.encoreticket.model.enums.ScreeningStatus;
import io.github.chenforegone.encoreticket.model.query.ScreeningProjection;
import io.github.chenforegone.encoreticket.model.query.ScreeningQueryFilter;
import io.github.chenforegone.encoreticket.model.vo.AdminScreeningDetailVO;
import io.github.chenforegone.encoreticket.model.vo.AdminScreeningSummaryVO;
import io.github.chenforegone.encoreticket.model.vo.ScreeningCreatedVO;
import io.github.chenforegone.encoreticket.model.vo.ScreeningDetailVO;
import io.github.chenforegone.encoreticket.model.vo.ScreeningSeatVO;
import io.github.chenforegone.encoreticket.model.vo.ScreeningSeatsVO;
import io.github.chenforegone.encoreticket.model.vo.ScreeningSummaryVO;
import io.github.chenforegone.encoreticket.service.rules.HallScheduleGuard;
import io.github.chenforegone.encoreticket.service.impl.OrderExpirationService;
import io.github.chenforegone.encoreticket.service.rules.ScreeningRules;
import io.github.chenforegone.encoreticket.service.ScreeningService;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 场次排片、编辑、状态转换及只读查询。
 *
 * @author c1342
 */
@Service
@Transactional(isolation = Isolation.READ_COMMITTED)
public class ScreeningServiceImpl implements ScreeningService {
    private static final Set<String> SORT_FIELDS = Set.of("startTime", "createTime", "saleStartTime", "saleEndTime");
    private final ScreeningMapper screenings;
    private final MovieMapper movies;
    private final HallScheduleGuard halls;
    private final SeatMapper seats;
    private final ScreeningSeatMapper screeningSeats;
    private final ScreeningQueryMapper mapper;
    private final ScreeningConverter converter;
    private final OrderExpirationService expirationService;
    private final Clock clock;

    public ScreeningServiceImpl(ScreeningMapper screenings, MovieMapper movies, HallScheduleGuard halls,
            SeatMapper seats, ScreeningSeatMapper screeningSeats,
            ScreeningQueryMapper mapper, ScreeningConverter converter,
            OrderExpirationService expirationService, Clock clock) {
        this.screenings = screenings;
        this.movies = movies;
        this.halls = halls;
        this.seats = seats;
        this.screeningSeats = screeningSeats;
        this.mapper = mapper;
        this.converter = converter;
        this.expirationService = expirationService;
        this.clock = clock;
    }

    @Override
    public ScreeningCreatedVO create(CreateScreeningRequest request) {
        long movieId = ScreeningRules.parseId(request.movieId());
        long hallId = ScreeningRules.parseId(request.hallId());
        BigDecimal price = ScreeningRules.parsePrice(request.price());
        Movie movie = claimMovies(movieId).get(movieId);
        List<Screening> schedule = halls.lockHalls(hallId);
        requireEnabledHall(hallId);
        LocalDateTime now = ScreeningRules.now(clock);
        boolean immediate = request.saleMode() == ScreeningSaleMode.IMMEDIATE;
        if (immediate) {
            requireOnline(movie);
        }
        ScreeningRules.Times times = ScreeningRules.plan(movie, request.startTime(), request.saleStartTime(), immediate, now);
        List<Seat> enabledSeats = requireSeats(hallId);
        requireNoOverlap(hallId, null, times.start(), times.end(), ScreeningRules.TURNAROUND_MINUTES);
        Screening value = new Screening();
        value.setMovieId(movieId);
        value.setHallId(hallId);
        value.setPrice(price);
        applyTimes(value, times);
        value.setAdmissionOpenTime(ScreeningRules.admission(request.admissionOpenTime(), null, times.start()));
        validateNeighbors(value, schedule, null);
        value.setStatus(immediate ? ScreeningStatus.ON_SALE : ScreeningStatus.WAIT_SALE);
        value.setVersion(0);
        value.setCreateTime(now.atZone(ScreeningRules.ZONE).toInstant());
        value.setUpdateTime(value.getCreateTime());
        screenings.insert(value);
        initializeSeats(value, enabledSeats);
        return new ScreeningCreatedVO(value.getId().toString());
    }

    @Override
    public AdminScreeningDetailVO update(String id, UpdateScreeningRequest request) {
        Screening original = find(ScreeningRules.parseId(id));
        requireVersion(original, request.version());
        requireState(original, ScreeningStatus.WAIT_SALE);
        long movieId = ScreeningRules.parseId(request.movieId());
        long hallId = ScreeningRules.parseId(request.hallId());
        BigDecimal price = ScreeningRules.parsePrice(request.price());
        Movie movie = claimMovies(original.getMovieId(), movieId).get(movieId);
        List<Screening> schedule = halls.lockHalls(original.getHallId(), hallId);
        requireEnabledHall(hallId);
        Screening current = schedule.stream().filter(item -> item.getId().equals(original.getId())).findFirst()
                .orElseThrow(() -> new ConflictException(ErrorCode.RESOURCE_CONFLICT));
        if (!current.getHallId().equals(original.getHallId()) || !current.getMovieId().equals(original.getMovieId())) {
            throw new ConflictException(ErrorCode.RESOURCE_CONFLICT);
        }
        requireVersion(current, request.version());
        requireState(current, ScreeningStatus.WAIT_SALE);
        LocalDateTime now = ScreeningRules.now(clock);
        requireSalesWindow(current, now);
        requireBeforeSaleStart(current, now);
        ScreeningRules.Times times = ScreeningRules.plan(movie, request.startTime(), request.saleStartTime(), false, now);
        List<Seat> enabledSeats = requireSeats(hallId);
        requireNoOverlap(hallId, current.getId(), times.start(), times.end(), ScreeningRules.TURNAROUND_MINUTES);
        Screening replacement = new Screening();
        replacement.setId(current.getId());
        replacement.setMovieId(movieId);
        replacement.setHallId(hallId);
        replacement.setPrice(price);
        applyTimes(replacement, times);
        replacement.setAdmissionOpenTime(ScreeningRules.admission(request.admissionOpenTime(), current.getAdmissionOpenTime(), times.start()));
        validateNeighbors(replacement, schedule, current.getId());
        if (screenings.replaceWaitSale(replacement, request.version(), now) != 1) {
            failUpdate(current.getId(), request.version());
        }
        // 已取得场次写入权；未来下单也必须竞争该行，再读取当前状态。
        requireBeforeSaleStart(current, ScreeningRules.now(clock));
        if (screenings.countOrders(current.getId()) > 0 || screenings.countOccupiedSeats(current.getId()) > 0) {
            throw new ConflictException(ErrorCode.RESOURCE_CONFLICT);
        }
        if (current.getHallId() != hallId) {
            screeningSeats.deleteForScreening(current.getId());
            initializeSeats(replacement, enabledSeats);
        }
        return getAdmin(id);
    }

    @Override
    public AdminScreeningDetailVO onSale(String id, ScreeningVersionRequest request) {
        Screening value = find(ScreeningRules.parseId(id));
        requireVersion(value, request.version());
        open(value, request.version(), false);
        return getAdmin(id);
    }

    @Override
    public AdminScreeningDetailVO offSale(String id, ScreeningVersionRequest request) {
        Screening value = find(ScreeningRules.parseId(id));
        requireVersion(value, request.version());
        requireState(value, ScreeningStatus.ON_SALE);
        LocalDateTime now = ScreeningRules.now(clock);
        requireNotEnded(value, now);
        change(value, ScreeningStatus.OFF_SALE, null, now);
        requireNotEnded(value, ScreeningRules.now(clock));
        return getAdmin(id);
    }

    @Override
    public AdminScreeningDetailVO close(String id, ScreeningVersionRequest request) {
        Screening value = find(ScreeningRules.parseId(id));
        requireVersion(value, request.version());
        requireClosable(value);
        halls.lockHalls(value.getHallId());
        LocalDateTime now = ScreeningRules.now(clock);
        requireNotEnded(value, now);
        if (screenings.claimForClose(value.getId(), request.version(), now) != 1) {
            failUpdate(value.getId(), request.version());
        }
        // 写锁与未来订单创建共用；检查完成前不会释放。
        if (screenings.countActiveOrders(value.getId()) > 0 || screenings.countOccupiedSeats(value.getId()) > 0) {
            throw new ConflictException(ErrorCode.RESOURCE_CONFLICT);
        }
        now = ScreeningRules.now(clock);
        requireNotEnded(value, now);
        if (screenings.finishClose(value.getId(), request.version() + 1, now) != 1) {
            throw new ConflictException(ErrorCode.SCREENING_STATUS_CONFLICT);
        }
        return getAdmin(id);
    }

    @Override
    public void advanceLifecycle(long id) {
        Screening value = find(id);
        if (value.getStatus() == ScreeningStatus.CLOSED || value.getStatus() == ScreeningStatus.ENDED) {
            return;
        }
        LocalDateTime now = ScreeningRules.now(clock);
        if (!value.getEndTime().isAfter(now)) {
            change(value, ScreeningStatus.ENDED, null, now);
        } else if (!value.getSaleEndTime().isAfter(now)) {
            if (value.getStatus() == ScreeningStatus.WAIT_SALE || value.getStatus() == ScreeningStatus.ON_SALE) {
                change(value, ScreeningStatus.OFF_SALE, null, now);
            }
        } else if (value.getStatus() == ScreeningStatus.WAIT_SALE && !value.getSaleStartTime().isAfter(now)) {
            open(value, value.getVersion(), true);
        }
    }

    private void open(Screening initial, Integer version, boolean automatic) {
        if (initial.getStatus() != ScreeningStatus.WAIT_SALE
                && (automatic || initial.getStatus() != ScreeningStatus.OFF_SALE)) {
            throw new ConflictException(ErrorCode.SCREENING_STATUS_CONFLICT);
        }
        Movie movie = claimMovies(initial.getMovieId()).get(initial.getMovieId());
        List<Screening> schedule = halls.lockHalls(initial.getHallId());
        requireEnabledHall(initial.getHallId());
        Screening value = schedule.stream().filter(item -> item.getId().equals(initial.getId())).findFirst()
                .orElseThrow(() -> new ConflictException(ErrorCode.RESOURCE_CONFLICT));
        if (!value.getMovieId().equals(initial.getMovieId())) {
            throw new ConflictException(ErrorCode.RESOURCE_CONFLICT);
        }
        if (value.getStatus() != ScreeningStatus.WAIT_SALE && (automatic || value.getStatus() != ScreeningStatus.OFF_SALE)) {
            throw new ConflictException(ErrorCode.SCREENING_STATUS_CONFLICT);
        }
        validateNeighbors(value, schedule, value.getId());
        requireVersion(value, version);
        requireOnline(movie);
        LocalDateTime now = ScreeningRules.now(clock);
        requireSalesWindow(value, now);
        if (automatic && value.getSaleStartTime().isAfter(now)) {
            throw new BusinessRuleException(ErrorCode.BUSINESS_RULE_VIOLATION);
        }
        if (value.getStartTime().toLocalDate().isBefore(movie.getReleaseDate())) {
            throw new BusinessRuleException(ErrorCode.BUSINESS_RULE_VIOLATION);
        }
        if (screenings.countAvailableSeats(value.getId()) == 0) {
            throw new BusinessRuleException(ErrorCode.BUSINESS_RULE_VIOLATION);
        }
        requireNoOverlap(value.getHallId(), value.getId(), value.getStartTime(), value.getEndTime(),
                value.getTurnaroundMinutesSnapshot());
        LocalDateTime saleStart = value.getSaleStartTime().isAfter(now) ? now : value.getSaleStartTime();
        change(value, ScreeningStatus.ON_SALE, saleStart, now);
        requireSalesWindow(value, ScreeningRules.now(clock));
    }

    private void requireEnabledHall(long hallId) {
        if (screenings.countEnabledHall(hallId) == 0) {
            throw new BusinessRuleException(ErrorCode.BUSINESS_RULE_VIOLATION);
        }
    }

    private void requireBeforeSaleStart(Screening value, LocalDateTime now) {
        if (!now.isBefore(value.getSaleStartTime())) {
            throw new ConflictException(ErrorCode.RESOURCE_CONFLICT);
        }
    }

    private void validateNeighbors(Screening candidate, List<Screening> schedule, Long excludedId) {
        for (Screening neighbor : schedule) {
            if (!neighbor.getHallId().equals(candidate.getHallId())
                    || neighbor.getId().equals(excludedId) || neighbor.getStatus() == ScreeningStatus.CLOSED) {
                continue;
            }
            int turnaround = neighbor.getTurnaroundMinutesSnapshot() == null
                    ? ScreeningRules.TURNAROUND_MINUTES : neighbor.getTurnaroundMinutesSnapshot();
            LocalDateTime admission = neighbor.getAdmissionOpenTime() == null
                    ? neighbor.getStartTime().minusMinutes(15) : neighbor.getAdmissionOpenTime();
            if (!neighbor.getEndTime().isAfter(candidate.getStartTime())) {
                if (candidate.getStartTime().isBefore(neighbor.getEndTime().plusMinutes(turnaround))
                        || candidate.getAdmissionOpenTime().isBefore(neighbor.getEndTime())) {
                    throw new BusinessRuleException(ErrorCode.BUSINESS_RULE_VIOLATION);
                }
            } else if (!neighbor.getStartTime().isBefore(candidate.getEndTime())) {
                if (candidate.getEndTime().plusMinutes(candidate.getTurnaroundMinutesSnapshot()).isAfter(neighbor.getStartTime())
                        || candidate.getEndTime().isAfter(admission)) {
                    throw new BusinessRuleException(ErrorCode.BUSINESS_RULE_VIOLATION);
                }
            } else {
                throw new ConflictException(ErrorCode.RESOURCE_CONFLICT);
            }
        }
    }

    private Map<Long, Movie> claimMovies(long... ids) {
        Map<Long, Movie> values = new LinkedHashMap<>();
        for (long id : Arrays.stream(ids).distinct().sorted().toArray()) {
            Movie movie = movies.selectById(id);
            if (movie == null) {
                throw new ResourceNotFoundException();
            }
            if (movies.claimForScheduling(id, movie.getVersion()) != 1) {
                throw new ConflictException(ErrorCode.MOVIE_VERSION_CONFLICT);
            }
            values.put(id, movies.selectById(id));
        }
        return values;
    }

    private List<Seat> requireSeats(long hallId) {
        List<Seat> values = seats.enabledInHall(hallId);
        if (values.isEmpty()) {
            throw new BusinessRuleException(ErrorCode.BUSINESS_RULE_VIOLATION);
        }
        return values;
    }

    private void initializeSeats(Screening screening, List<Seat> physicalSeats) {
        Instant timestamp = clock.instant().truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        List<ScreeningSeat> batch = new ArrayList<>();
        for (Seat physical : physicalSeats) {
            ScreeningSeat seat = new ScreeningSeat();
            seat.setId(IdWorker.getId());
            seat.setScreeningId(screening.getId());
            seat.setSeatId(physical.getId());
            seat.setCreateTime(timestamp);
            seat.setUpdateTime(timestamp);
            batch.add(seat);
            if (batch.size() == 500) {
                screeningSeats.insertSeats(batch);
                batch.clear();
            }
        }
        if (!batch.isEmpty()) {
            screeningSeats.insertSeats(batch);
        }
    }

    private void requireNoOverlap(long hallId, Long excludedId, LocalDateTime start,
            LocalDateTime end, int turnaround) {
        if (screenings.countOverlap(hallId, excludedId, start, end.plusMinutes(turnaround)) > 0) {
            throw new ConflictException(ErrorCode.RESOURCE_CONFLICT);
        }
    }

    private void change(Screening value, ScreeningStatus target, LocalDateTime saleStart, LocalDateTime now) {
        if (screenings.changeStatus(value.getId(), value.getVersion(), value.getStatus().getCode(),
                target.getCode(), saleStart, now) != 1) {
            failUpdate(value.getId(), value.getVersion());
        }
    }

    private void failUpdate(long id, Integer version) {
        Screening latest = find(id);
        requireVersion(latest, version);
        throw new ConflictException(ErrorCode.SCREENING_STATUS_CONFLICT);
    }

    private Screening find(long id) {
        Screening value = screenings.selectById(id);
        if (value == null) {
            throw new ResourceNotFoundException();
        }
        return value;
    }

    private static void requireVersion(Screening value, Integer expected) {
        if (expected == null || expected < 0) {
            throw new InvalidRequestException(ErrorCode.INVALID_ARGUMENT);
        }
        if (!expected.equals(value.getVersion())) {
            throw new ConflictException(ErrorCode.RESOURCE_CONFLICT);
        }
    }

    private static void requireState(Screening value, ScreeningStatus expected) {
        if (value.getStatus() != expected) {
            throw new ConflictException(ErrorCode.SCREENING_STATUS_CONFLICT);
        }
    }

    private static void requireClosable(Screening value) {
        if (value.getStatus() != ScreeningStatus.WAIT_SALE && value.getStatus() != ScreeningStatus.OFF_SALE) {
            throw new ConflictException(ErrorCode.SCREENING_STATUS_CONFLICT);
        }
    }

    private static void requireOnline(Movie movie) {
        if (movie.getStatus() != MovieStatus.ONLINE) {
            throw new ConflictException(ErrorCode.MOVIE_NOT_ONLINE);
        }
    }

    private static void requireSalesWindow(Screening value, LocalDateTime now) {
        if (!value.getSaleEndTime().isAfter(now)) {
            throw new BusinessRuleException(ErrorCode.BUSINESS_RULE_VIOLATION);
        }
    }

    private static void requireNotEnded(Screening value, LocalDateTime now) {
        if (!value.getEndTime().isAfter(now)) {
            throw new ConflictException(ErrorCode.SCREENING_STATUS_CONFLICT);
        }
    }

    private static void applyTimes(Screening value, ScreeningRules.Times times) {
        value.setStartTime(times.start());
        value.setEndTime(times.end());
        value.setSaleStartTime(times.saleStart());
        value.setSaleEndTime(times.saleEnd());
        value.setDurationMinutesSnapshot(times.duration());
        value.setTurnaroundMinutesSnapshot(ScreeningRules.TURNAROUND_MINUTES);
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<ScreeningSummaryVO> listPublic(PublicScreeningQueryRequest request) {
        validatePage(request.page(), request.pageSize());
        LocalDateTime[] dates = dateBounds(request.screeningDate());
        ScreeningQueryFilter filter = new ScreeningQueryFilter(optionalId(request.movieId()), optionalId(request.hallId()),
                null, dates[0], dates[1], null, null, null, null, null, null, "startTime", "asc");
        LocalDateTime now = ScreeningRules.now(clock);
        long total = mapper.countPublic(filter, now);
        List<ScreeningSummaryVO> items = mapper.selectPublic(filter, now, offset(request.page(), request.pageSize()), request.pageSize())
                .stream().map(s -> converter.toSummaryVO(s, now)).toList();
        return new PageResponse<>(items, request.page(), request.pageSize(), total);
    }

    @Override
    @Transactional(readOnly = true)
    public ScreeningDetailVO getPublic(String screeningId) {
        LocalDateTime now = ScreeningRules.now(clock);
        return converter.toDetailVO(requirePublic(ScreeningRules.parseId(screeningId), now), now);
    }

    @Override
    @Transactional(readOnly = true, isolation = Isolation.READ_COMMITTED)
    public ScreeningSeatsVO getSeats(String screeningId) {
        long id = ScreeningRules.parseId(screeningId);
        requirePublic(id, ScreeningRules.now(clock));
        expirationService.expireForScreening(id);
        LocalDateTime now = ScreeningRules.now(clock);
        ScreeningProjection screening = requirePublic(id, now);
        List<ScreeningSeatVO> seats = mapper.selectSeats(id).stream().map(converter::toSeatVO).toList();
        return new ScreeningSeatsVO(screeningId, screening.getStatus().name(),
                converter.canPurchase(screening, now), converter.toOffset(now), seats);
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<AdminScreeningSummaryVO> listAdmin(AdminScreeningQueryRequest request) {
        validatePage(request.page(), request.pageSize());
        if (!SORT_FIELDS.contains(request.sortBy()) || !Set.of("asc", "desc").contains(request.sortOrder())) {
            throw invalid();
        }
        LocalDateTime[] dates = dateBounds(request.screeningDate());
        LocalDateTime[] start = timeBounds(request.startTimeFrom(), request.startTimeTo());
        LocalDateTime[] saleStart = timeBounds(request.saleStartTimeFrom(), request.saleStartTimeTo());
        LocalDateTime[] saleEnd = timeBounds(request.saleEndTimeFrom(), request.saleEndTimeTo());
        ScreeningQueryFilter filter = new ScreeningQueryFilter(optionalId(request.movieId()), optionalId(request.hallId()),
                request.status() == null ? null : request.status().getCode(), dates[0], dates[1],
                start[0], start[1], saleStart[0], saleStart[1], saleEnd[0], saleEnd[1], request.sortBy(), request.sortOrder());
        long total = mapper.countAdmin(filter);
        List<AdminScreeningSummaryVO> items = mapper.selectAdmin(filter, offset(request.page(), request.pageSize()), request.pageSize())
                .stream().map(converter::toAdminSummaryVO).toList();
        return new PageResponse<>(items, request.page(), request.pageSize(), total);
    }

    @Override
    @Transactional(readOnly = true)
    public AdminScreeningDetailVO getAdmin(String screeningId) {
        ScreeningProjection value = mapper.findAdmin(ScreeningRules.parseId(screeningId));
        if (value == null) {
            throw new ResourceNotFoundException();
        }
        return converter.toAdminDetailVO(value);
    }

    private ScreeningProjection requirePublic(long id, LocalDateTime now) {
        ScreeningProjection value = mapper.findPublic(id, now);
        if (value == null) {
            throw new ResourceNotFoundException();
        }
        return value;
    }

    private Long optionalId(String value) {
        return value == null ? null : ScreeningRules.parseId(value);
    }

    private LocalDateTime[] timeBounds(OffsetDateTime from, OffsetDateTime to) {
        LocalDateTime first = from == null ? null : ScreeningRules.local(from);
        LocalDateTime last = to == null ? null : ScreeningRules.local(to);
        if (first != null && last != null && !first.isBefore(last)) {
            throw invalid();
        }
        return new LocalDateTime[] {first, last};
    }

    private LocalDateTime[] dateBounds(LocalDate date) {
        if (date == null) {
            return new LocalDateTime[] {null, null};
        }
        if (date.getYear() < 1000 || date.getYear() > 9999) {
            throw invalid();
        }
        try {
            LocalDateTime to = date.equals(LocalDate.of(9999, 12, 31)) ? null : date.plusDays(1).atStartOfDay();
            return new LocalDateTime[] {date.atStartOfDay(), to};
        } catch (DateTimeException exception) {
            throw invalid();
        }
    }

    private void validatePage(Integer page, Integer size) {
        if (page == null || size == null || page < 1 || size < 1 || size > 100) {
            throw invalid();
        }
    }

    private long offset(int page, int size) {
        return ((long) page - 1) * size;
    }

    private InvalidRequestException invalid() {
        return new InvalidRequestException(ErrorCode.INVALID_ARGUMENT);
    }
}
