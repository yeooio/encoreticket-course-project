package io.github.chenforegone.encoreticket.controller.admin;

import io.github.chenforegone.encoreticket.common.response.ApiResponse;
import io.github.chenforegone.encoreticket.common.response.PageResponse;
import io.github.chenforegone.encoreticket.model.dto.CreateMovieRequest;
import io.github.chenforegone.encoreticket.model.dto.MovieQueryRequest;
import io.github.chenforegone.encoreticket.model.dto.OfflineMovieRequest;
import io.github.chenforegone.encoreticket.model.dto.PublishMovieRequest;
import io.github.chenforegone.encoreticket.model.dto.UpdateMovieRequest;
import io.github.chenforegone.encoreticket.model.vo.AdminMovieDetailVO;
import io.github.chenforegone.encoreticket.model.vo.MovieCreatedVO;
import io.github.chenforegone.encoreticket.model.vo.MovieSummaryVO;
import io.github.chenforegone.encoreticket.service.MovieService;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 管理员电影管理接口。
 *
 * @author c1342
 */
@RestController("adminMovieController")
@RequestMapping("/admin/movies")
public class AdminMovieController {

    private final MovieService movieService;

    public AdminMovieController(MovieService movieService) {
        this.movieService = movieService;
    }

    @PostMapping
    public ResponseEntity<ApiResponse<MovieCreatedVO>> create(
            @Valid @RequestBody CreateMovieRequest request
    ) {
        MovieCreatedVO result = movieService.create(request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .header(HttpHeaders.LOCATION, "/admin/movies/" + result.id())
                .body(ApiResponse.of(result));
    }

    @GetMapping
    public ResponseEntity<ApiResponse<PageResponse<MovieSummaryVO>>> list(
            @Valid MovieQueryRequest request
    ) {
        return ResponseEntity.ok(ApiResponse.of(movieService.listAdmin(request)));
    }

    @GetMapping("/{movieId}")
    public ResponseEntity<ApiResponse<AdminMovieDetailVO>> get(@PathVariable String movieId) {
        return ResponseEntity.ok(ApiResponse.of(movieService.getAdmin(movieId)));
    }

    @PutMapping("/{movieId}")
    public ResponseEntity<ApiResponse<AdminMovieDetailVO>> update(
            @PathVariable String movieId,
            @Valid @RequestBody UpdateMovieRequest request
    ) {
        return ResponseEntity.ok(ApiResponse.of(movieService.update(movieId, request)));
    }

    @PostMapping("/{movieId}/publish")
    public ResponseEntity<Void> publish(
            @PathVariable String movieId,
            @Valid @RequestBody PublishMovieRequest request
    ) {
        movieService.publish(movieId, request);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{movieId}/offline")
    public ResponseEntity<Void> offline(
            @PathVariable String movieId,
            @Valid @RequestBody OfflineMovieRequest request
    ) {
        movieService.offline(movieId, request);
        return ResponseEntity.noContent().build();
    }
}
