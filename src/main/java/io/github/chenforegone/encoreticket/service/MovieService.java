package io.github.chenforegone.encoreticket.service;

import io.github.chenforegone.encoreticket.common.response.PageResponse;
import io.github.chenforegone.encoreticket.model.dto.CreateMovieRequest;
import io.github.chenforegone.encoreticket.model.dto.MovieQueryRequest;
import io.github.chenforegone.encoreticket.model.dto.OfflineMovieRequest;
import io.github.chenforegone.encoreticket.model.dto.PublishMovieRequest;
import io.github.chenforegone.encoreticket.model.dto.UpdateMovieRequest;
import io.github.chenforegone.encoreticket.model.vo.AdminMovieDetailVO;
import io.github.chenforegone.encoreticket.model.vo.MovieCreatedVO;
import io.github.chenforegone.encoreticket.model.vo.MovieDetailVO;
import io.github.chenforegone.encoreticket.model.vo.MovieSummaryVO;

/**
 * 电影业务服务。
 *
 * @author c1342
 */
public interface MovieService {

    PageResponse<MovieSummaryVO> listPublic(MovieQueryRequest request);

    MovieDetailVO getPublic(String movieId);

    PageResponse<MovieSummaryVO> listAdmin(MovieQueryRequest request);

    AdminMovieDetailVO getAdmin(String movieId);

    MovieCreatedVO create(CreateMovieRequest request);

    AdminMovieDetailVO update(String movieId, UpdateMovieRequest request);

    void publish(String movieId, PublishMovieRequest request);

    void offline(String movieId, OfflineMovieRequest request);
}
