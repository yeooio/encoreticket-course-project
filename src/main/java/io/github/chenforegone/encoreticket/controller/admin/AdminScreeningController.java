package io.github.chenforegone.encoreticket.controller.admin;

import io.github.chenforegone.encoreticket.common.response.ApiResponse;
import io.github.chenforegone.encoreticket.common.response.PageResponse;
import io.github.chenforegone.encoreticket.model.dto.AdminScreeningQueryRequest;
import io.github.chenforegone.encoreticket.model.dto.CreateScreeningRequest;
import io.github.chenforegone.encoreticket.model.dto.UpdateScreeningRequest;
import io.github.chenforegone.encoreticket.model.dto.ScreeningVersionRequest;
import io.github.chenforegone.encoreticket.model.vo.AdminScreeningDetailVO;
import io.github.chenforegone.encoreticket.model.vo.AdminScreeningSummaryVO;
import io.github.chenforegone.encoreticket.model.vo.ScreeningCreatedVO;
import io.github.chenforegone.encoreticket.service.ScreeningService;
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
 * 管理员场次接口；由现有 /admin/** 安全规则独立保护。
 *
 * @author c1342
 */
@RestController("adminScreeningController")
@RequestMapping("/admin/screenings")
public class AdminScreeningController {
    private final ScreeningService screeningService;

    public AdminScreeningController(ScreeningService screeningService) {
        this.screeningService = screeningService;
    }

    @PostMapping
    public ResponseEntity<ApiResponse<ScreeningCreatedVO>> create(@Valid @RequestBody CreateScreeningRequest request) {
        ScreeningCreatedVO created = screeningService.create(request);
        return ResponseEntity.status(HttpStatus.CREATED).header(HttpHeaders.LOCATION, "/admin/screenings/" + created.id())
                .body(ApiResponse.of(created));
    }

    @GetMapping
    public ResponseEntity<ApiResponse<PageResponse<AdminScreeningSummaryVO>>> list(
            @Valid AdminScreeningQueryRequest request) {
        return ResponseEntity.ok(ApiResponse.of(screeningService.listAdmin(request)));
    }

    @GetMapping("/{screeningId}")
    public ResponseEntity<ApiResponse<AdminScreeningDetailVO>> get(@PathVariable String screeningId) {
        return ResponseEntity.ok(ApiResponse.of(screeningService.getAdmin(screeningId)));
    }

    @PutMapping("/{screeningId}")
    public ResponseEntity<ApiResponse<AdminScreeningDetailVO>> update(@PathVariable String screeningId,
            @Valid @RequestBody UpdateScreeningRequest request) {
        return ResponseEntity.ok(ApiResponse.of(screeningService.update(screeningId, request)));
    }

    @PostMapping("/{screeningId}/on-sale")
    public ResponseEntity<ApiResponse<AdminScreeningDetailVO>> onSale(@PathVariable String screeningId,
            @Valid @RequestBody ScreeningVersionRequest request) {
        return ResponseEntity.ok(ApiResponse.of(screeningService.onSale(screeningId, request)));
    }

    @PostMapping("/{screeningId}/off-sale")
    public ResponseEntity<ApiResponse<AdminScreeningDetailVO>> offSale(@PathVariable String screeningId,
            @Valid @RequestBody ScreeningVersionRequest request) {
        return ResponseEntity.ok(ApiResponse.of(screeningService.offSale(screeningId, request)));
    }

    @PostMapping("/{screeningId}/close")
    public ResponseEntity<ApiResponse<AdminScreeningDetailVO>> close(@PathVariable String screeningId,
            @Valid @RequestBody ScreeningVersionRequest request) {
        return ResponseEntity.ok(ApiResponse.of(screeningService.close(screeningId, request)));
    }
}
