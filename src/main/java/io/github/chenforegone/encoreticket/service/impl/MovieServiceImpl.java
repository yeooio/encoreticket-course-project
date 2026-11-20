package io.github.chenforegone.encoreticket.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.github.chenforegone.encoreticket.common.error.ErrorCode;
import io.github.chenforegone.encoreticket.common.exception.BusinessRuleException;
import io.github.chenforegone.encoreticket.common.exception.ConflictException;
import io.github.chenforegone.encoreticket.common.exception.InvalidRequestException;
import io.github.chenforegone.encoreticket.common.exception.ResourceNotFoundException;
import io.github.chenforegone.encoreticket.common.response.PageResponse;
import io.github.chenforegone.encoreticket.converter.MovieConverter;
import io.github.chenforegone.encoreticket.mapper.MovieMapper;
import io.github.chenforegone.encoreticket.model.dto.CreateMovieRequest;
import io.github.chenforegone.encoreticket.model.dto.MovieQueryRequest;
import io.github.chenforegone.encoreticket.model.dto.OfflineMovieRequest;
import io.github.chenforegone.encoreticket.model.dto.PublishMovieRequest;
import io.github.chenforegone.encoreticket.model.dto.UpdateMovieRequest;
import io.github.chenforegone.encoreticket.model.entity.Movie;
import io.github.chenforegone.encoreticket.model.enums.MovieStatus;
import io.github.chenforegone.encoreticket.model.vo.AdminMovieDetailVO;
import io.github.chenforegone.encoreticket.model.vo.MovieCreatedVO;
import io.github.chenforegone.encoreticket.model.vo.MovieDetailVO;
import io.github.chenforegone.encoreticket.model.vo.MovieSummaryVO;
import io.github.chenforegone.encoreticket.service.MovieService;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;

/**
 * 电影目录业务实现。
 *
 * @author c1342
 */
@Service
public class MovieServiceImpl implements MovieService {

    private static final List<String> PUBLIC_SORT_FIELDS = List.of("releaseDate", "title");
    private static final List<String> ADMIN_SORT_FIELDS = List.of("releaseDate", "title", "publishAt", "createTime");

    private final MovieMapper movieMapper;
    private final MovieConverter movieConverter;

    public MovieServiceImpl(MovieMapper movieMapper, MovieConverter movieConverter) {
        this.movieMapper = movieMapper;
        this.movieConverter = movieConverter;
    }

    //查询公共部分电影列表
    @Override
    public PageResponse<MovieSummaryVO> listPublic(MovieQueryRequest request) {
        //查询电影,在这前对参数进行校验
        validateQuery(request, PUBLIC_SORT_FIELDS);
        return page(request, MovieStatus.ONLINE);
    }

    //用户查询单个电影详情
    @Override
    public MovieDetailVO getPublic(String movieId) {
        //通过id查询电影
        Movie movie = findMovie(movieId);
        //判断电影是否上架
        if (movie.getStatus() != MovieStatus.ONLINE) {
            throw new ResourceNotFoundException();
        }
        return movieConverter.toDetailVO(movie);
    }

    //管理员查询电影列表
    @Override
    public PageResponse<MovieSummaryVO> listAdmin(MovieQueryRequest request) {
        //对查询参数进行校验
        validateQuery(request, ADMIN_SORT_FIELDS);
        //返回分页结果
        return page(request, request.status());
    }

    //管理员查询单个电影详情
    @Override
    public AdminMovieDetailVO getAdmin(String movieId) {
        //查询电影
        return movieConverter.toAdminDetailVO(findMovie(movieId));
    }

    //管理员创建电影
    @Override
    @Transactional
    public MovieCreatedVO create(CreateMovieRequest request) {
        Instant publishAt = request.publishAt().toInstant();
        //判断电影发布时间是否合适
        validatePublishAt(publishAt);
        //确保电影同一发布日期的电影名唯一
        ensureUnique(request.title(), request.releaseDate(), null);
        //构建电影实体对象
        Movie movie = new Movie();
        movie.setTitle(request.title());
        movie.setDescription(request.description());
        movie.setReleaseDate(request.releaseDate());
        movie.setDurationMinutes(request.durationMinutes());
        movie.setPublishAt(publishAt);
        movie.setStatus(MovieStatus.OFFLINE);
        movie.setVersion(0);
        try {
            movieMapper.insert(movie);
        } catch (DuplicateKeyException exception) {
            //如果抛出异常，则说明电影名和发布日期冲突
            throw new ConflictException(ErrorCode.MOVIE_TITLE_RELEASE_DATE_CONFLICT, exception);
        }
        return new MovieCreatedVO(movie.getId().toString());
    }

    //管理员更新电影
    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public AdminMovieDetailVO update(String movieId, UpdateMovieRequest request) {
        //查询电影
        Movie movie = findMovie(movieId);
        LocalDate originalReleaseDate = movie.getReleaseDate();
        //电影已经上映,无法进行修改
        if (movie.getStatus() != MovieStatus.OFFLINE) {
            throw new ConflictException(ErrorCode.MOVIE_ALREADY_RELEASED);
        }
        //查看当前当前版本号
        ensureVersion(movie, request.version());
        //确保电影同一发布日期的电影名唯一
        ensureUnique(request.title(), request.releaseDate(), movie.getId());
        //更新电影信息
        Instant publishAt = toInstant(request.publishAt());
        validatePublishAt(publishAt);
        movie.setTitle(request.title());
        movie.setDescription(request.description());
        movie.setReleaseDate(request.releaseDate());
        movie.setPublishAt(publishAt);
        movie.setDurationMinutes(request.durationMinutes());
        //更新失败,说明其他线程已经修改当前线程
        if (movieMapper.updateById(movie) == 0) {
            throw new ConflictException(ErrorCode.MOVIE_VERSION_CONFLICT);
        }
        // 获得电影行的版本写入权后再当前读排片，避免与场次创建互相穿透。
        if (!request.releaseDate().equals(originalReleaseDate)
                && movieMapper.countScreeningsBeforeRelease(movie.getId(), request.releaseDate()) > 0) {
            throw new ConflictException(ErrorCode.MOVIE_SCREENING_CONFLICT);
        }
        //返回电影更新之后的详细视图
        return movieConverter.toAdminDetailVO(movie);
    }

    @Override
    @Transactional
    public void publish(String movieId, PublishMovieRequest request) {
        Movie movie = findMovie(movieId);
        if (movie.getStatus() != MovieStatus.OFFLINE) {
            throw new ConflictException(ErrorCode.MOVIE_ALREADY_RELEASED);
        }
        ensureVersion(movie, request.version());
        movie.setStatus(MovieStatus.ONLINE);
        movie.setPublishAt(null);
        if (movieMapper.updateById(movie) == 0) {
            throw new ConflictException(ErrorCode.MOVIE_VERSION_CONFLICT);
        }
    }

    //下架电影
    @Override
    @Transactional
    public void offline(String movieId, OfflineMovieRequest request) {
        //查询电影
        Movie movie = findMovie(movieId);
        //判断电影是否已经下架
        if (movie.getStatus() == MovieStatus.OFFLINE) {
            throw new ConflictException(ErrorCode.MOVIE_ALREADY_OFFLINE);
        }
        //判断同一时刻是否已经存在其他的线程已经修改电影
        ensureVersion(movie, request.version());
        movie.setStatus(MovieStatus.OFFLINE);
        movie.setPublishAt(null);
        if (movieMapper.updateById(movie) == 0) {
            throw new ConflictException(ErrorCode.MOVIE_VERSION_CONFLICT);
        }
    }

    //分页查询封装方法
    private PageResponse<MovieSummaryVO> page(MovieQueryRequest request, MovieStatus status) {
        //构造page对象
        Page<Movie> page = new Page<>(request.page(), request.pageSize());
        //通过page分页查询
        IPage<Movie> result = movieMapper.selectMoviePage(page, status, request.title(), request.releaseDateFrom(),
                request.releaseDateTo(), toInstant(request.publishAtFrom()),
                toInstant(request.publishAtTo()), request.sortBy(), request.sortOrder());
        List<MovieSummaryVO> items = result.getRecords().stream().map(movieConverter::toSummaryVO).toList();
        return new PageResponse<>(items, request.page(), request.pageSize(), result.getTotal());
    }

    private Movie findMovie(String movieId) {
        long id;
        //对id合法性进行校验如果输入"abc"非法参数的时候抛出异常
        try {
            id = Long.parseLong(movieId);
        } catch (NumberFormatException exception) {
            throw new InvalidRequestException(ErrorCode.INVALID_ARGUMENT);
        }
        //查询电影,电影不存在抛出异常
        Movie movie = movieMapper.selectById(id);
        if (movie == null) {
            throw new ResourceNotFoundException();
        }
        return movie;
    }

    private void ensureUnique(String title, LocalDate releaseDate, Long ignoredId) {
        //确保当前的电影唯一
        LambdaQueryWrapper<Movie> wrapper = new LambdaQueryWrapper<Movie>()
                .eq(Movie::getTitle, title)
                .eq(Movie::getReleaseDate, releaseDate);
        if (ignoredId != null) {
            wrapper.ne(Movie::getId, ignoredId);
        }
        if (movieMapper.selectCount(wrapper) > 0) {
            throw new ConflictException(ErrorCode.MOVIE_TITLE_RELEASE_DATE_CONFLICT);
        }
    }

    private static void ensureVersion(Movie movie, Integer expectedVersion) {
        if (expectedVersion == null || !expectedVersion.equals(movie.getVersion())) {
            throw new ConflictException(ErrorCode.MOVIE_VERSION_CONFLICT);
        }
    }

    private static void validateQuery(MovieQueryRequest request, List<String> allowedSortFields) {
        //判断开始日期前后时间是否冲突
        if (request.releaseDateFrom() != null && request.releaseDateTo() != null
                && request.releaseDateFrom().isAfter(request.releaseDateTo())) {
            throw new InvalidRequestException(ErrorCode.INVALID_ARGUMENT);
        }
        //判断日期前后时间是否冲突
        if (request.publishAtFrom() != null && request.publishAtTo() != null
                && request.publishAtFrom().isAfter(request.publishAtTo())) {
            throw new InvalidRequestException(ErrorCode.INVALID_ARGUMENT);
        }
        //判断当前排序的字段是否是指定字段之内
        if (!allowedSortFields.contains(request.sortBy())
                || !Arrays.asList("asc", "desc").contains(request.sortOrder().toLowerCase())) {
            throw new InvalidRequestException(ErrorCode.INVALID_ARGUMENT);
        }
    }

    //将时间转换成Instant(UTC标准时间)
    private static Instant toInstant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }

    //判断发布时间是否有效
    private static void validatePublishAt(Instant publishAt) {
        if (publishAt != null && publishAt.isBefore(Instant.now())) {
            throw new BusinessRuleException(ErrorCode.MOVIE_PUBLISH_TIME_INVALID);
        }
    }
}
