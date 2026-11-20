package io.github.chenforegone.encoreticket.controller.user;

import io.github.chenforegone.encoreticket.common.response.ApiResponse;
import io.github.chenforegone.encoreticket.common.response.PageResponse;
import io.github.chenforegone.encoreticket.model.dto.PublicScreeningQueryRequest;
import io.github.chenforegone.encoreticket.model.vo.ScreeningDetailVO;
import io.github.chenforegone.encoreticket.model.vo.ScreeningSeatsVO;
import io.github.chenforegone.encoreticket.model.vo.ScreeningSummaryVO;
import io.github.chenforegone.encoreticket.service.ScreeningService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 用户端场次查询接口，公开可见性由查询服务统一决定。
 *
 * @author c1342
 */
@RestController
@RequestMapping("/screenings")
public class UserScreeningController {
    private final ScreeningService screeningService;

    public UserScreeningController(ScreeningService screeningService) {
        this.screeningService = screeningService;
    }

    @GetMapping
    public ResponseEntity<ApiResponse<PageResponse<ScreeningSummaryVO>>> list(
            @Valid PublicScreeningQueryRequest request) {
        return ResponseEntity.ok(ApiResponse.of(screeningService.listPublic(request)));
    }

    @GetMapping("/{screeningId}")
    public ResponseEntity<ApiResponse<ScreeningDetailVO>> get(@PathVariable String screeningId) {
        return ResponseEntity.ok(ApiResponse.of(screeningService.getPublic(screeningId)));
    }

    @GetMapping("/{screeningId}/seats")
    public ResponseEntity<ApiResponse<ScreeningSeatsVO>> seats(@PathVariable String screeningId) {
        return ResponseEntity.ok(ApiResponse.of(screeningService.getSeats(screeningId)));
    }
}
