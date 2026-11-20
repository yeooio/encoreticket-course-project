package io.github.chenforegone.encoreticket.controller.admin;

import io.github.chenforegone.encoreticket.common.response.ApiResponse;
import io.github.chenforegone.encoreticket.model.dto.CreateHallRequest;
import io.github.chenforegone.encoreticket.model.dto.UpdateHallRequest;
import io.github.chenforegone.encoreticket.model.dto.HallVersionRequest;
import io.github.chenforegone.encoreticket.model.vo.HallDetailVO;
import io.github.chenforegone.encoreticket.model.vo.SeatLayoutVO;
import io.github.chenforegone.encoreticket.model.vo.SeatVO;
import io.github.chenforegone.encoreticket.service.HallService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * 影厅和物理座位管理接口，由 /admin/** 的 ADMIN 规则保护。
 * @author c1342
 */
@RestController
@RequestMapping("/admin/halls")
public class AdminHallController {
    private final HallService service;
    public AdminHallController(HallService service) { this.service = service; }

    @PostMapping
    public ResponseEntity<ApiResponse<HallDetailVO>> create(@Valid @RequestBody CreateHallRequest request) {
        var created = service.create(request);
        return ResponseEntity.created(java.net.URI.create("/admin/halls/" + created.hallId())).body(ApiResponse.of(created));
    }

    @GetMapping
    public ApiResponse<io.github.chenforegone.encoreticket.common.response.PageResponse<HallDetailVO>> list(
            @Valid io.github.chenforegone.encoreticket.model.dto.HallQueryRequest request) {
        return ApiResponse.of(service.list(request));
    }

    @GetMapping("/{hallId}")
    public ApiResponse<HallDetailVO> get(@PathVariable String hallId) { return ApiResponse.of(service.get(hallId)); }

    @PutMapping("/{hallId}")
    public ApiResponse<HallDetailVO> rename(@PathVariable String hallId, @Valid @RequestBody UpdateHallRequest request) {
        return ApiResponse.of(service.rename(hallId, request));
    }

    @PostMapping("/{hallId}/enable")
    public ApiResponse<HallDetailVO> enable(@PathVariable String hallId, @Valid @RequestBody HallVersionRequest request) {
        return ApiResponse.of(service.enable(hallId, request));
    }

    @PostMapping("/{hallId}/disable")
    public ApiResponse<HallDetailVO> disable(@PathVariable String hallId, @Valid @RequestBody HallVersionRequest request) {
        return ApiResponse.of(service.disable(hallId, request));
    }

    @GetMapping("/{hallId}/seats")
    public ApiResponse<SeatLayoutVO> layout(@PathVariable String hallId) { return ApiResponse.of(service.layout(hallId)); }

    @PostMapping("/{hallId}/seats")
    public ResponseEntity<ApiResponse<io.github.chenforegone.encoreticket.model.vo.SeatsCreatedVO>> addSeats(
            @PathVariable String hallId, @Valid @RequestBody io.github.chenforegone.encoreticket.model.dto.CreateSeatsRequest request) {
        return ResponseEntity.status(201).body(ApiResponse.of(service.addSeats(hallId, request)));
    }

    @PostMapping("/{hallId}/seats/{seatId}/enable")
    public ApiResponse<SeatVO> enableSeat(@PathVariable String hallId, @PathVariable String seatId,
            @Valid @RequestBody HallVersionRequest request) { return ApiResponse.of(service.enableSeat(hallId, seatId, request)); }

    @PostMapping("/{hallId}/seats/{seatId}/disable")
    public ApiResponse<SeatVO> disableSeat(@PathVariable String hallId, @PathVariable String seatId,
            @Valid @RequestBody HallVersionRequest request) { return ApiResponse.of(service.disableSeat(hallId, seatId, request)); }
}
