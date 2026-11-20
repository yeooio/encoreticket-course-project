package io.github.chenforegone.encoreticket.controller.admin;

import io.github.chenforegone.encoreticket.common.response.ApiResponse;
import io.github.chenforegone.encoreticket.model.dto.CreateScreeningRequest;
import io.github.chenforegone.encoreticket.model.dto.UpdateScreeningRequest;
import io.github.chenforegone.encoreticket.model.vo.AdminScreeningDetailVO;
import io.github.chenforegone.encoreticket.service.ScreeningService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 管理员场次配置接口入口。
 *
 * @author c1342
 */
@Validated
public class AdminScreeningConfigurationController {

    private final ScreeningService screeningService;

    public AdminScreeningConfigurationController(ScreeningService screeningService) {
        this.screeningService = screeningService;
    }

    @PostMapping
    public ApiResponse<io.github.chenforegone.encoreticket.model.vo.ScreeningCreatedVO> create(@Valid @RequestBody CreateScreeningRequest request) {
        return ApiResponse.of(screeningService.create(request));
    }

    @PutMapping("/{screeningId}")
    public ApiResponse<AdminScreeningDetailVO> update(
            @PathVariable @Positive Long screeningId,
            @Valid @RequestBody UpdateScreeningRequest request
    ) {
        return ApiResponse.of(screeningService.update(screeningId.toString(), request));
    }

    @GetMapping("/{screeningId}")
    public ApiResponse<AdminScreeningDetailVO> getAdminDetail(@PathVariable @Positive Long screeningId) {
        return ApiResponse.of(screeningService.getAdmin(screeningId.toString()));
    }
}
