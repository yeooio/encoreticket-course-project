package io.github.chenforegone.encoreticket.controller.user;

import io.github.chenforegone.encoreticket.common.response.ApiResponse;
import io.github.chenforegone.encoreticket.common.response.PageResponse;
import io.github.chenforegone.encoreticket.model.dto.MovieQueryRequest;
import io.github.chenforegone.encoreticket.model.vo.MovieDetailVO;
import io.github.chenforegone.encoreticket.model.vo.MovieSummaryVO;
import io.github.chenforegone.encoreticket.service.MovieService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 用户端电影查询接口。
 *
 * @author c1342
 */
@RestController("userMovieController")
@RequestMapping("/movies")
public class UserMovieController {

    private final MovieService movieService;

    public UserMovieController(MovieService movieService) {
        this.movieService = movieService;
    }

    @GetMapping
    public ResponseEntity<ApiResponse<PageResponse<MovieSummaryVO>>> list(
            @Valid MovieQueryRequest request
    ) {
        return ResponseEntity.ok(ApiResponse.of(movieService.listPublic(request)));
    }

    @GetMapping("/{movieId}")
    public ResponseEntity<ApiResponse<MovieDetailVO>> get(@PathVariable String movieId) {
        return ResponseEntity.ok(ApiResponse.of(movieService.getPublic(movieId)));
    }
}
