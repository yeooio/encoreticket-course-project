package io.github.chenforegone.encoreticket.service;

import io.github.chenforegone.encoreticket.common.response.PageResponse;
import io.github.chenforegone.encoreticket.model.dto.AdminScreeningQueryRequest;
import io.github.chenforegone.encoreticket.model.dto.CreateScreeningRequest;
import io.github.chenforegone.encoreticket.model.dto.PublicScreeningQueryRequest;
import io.github.chenforegone.encoreticket.model.dto.ScreeningVersionRequest;
import io.github.chenforegone.encoreticket.model.dto.UpdateScreeningRequest;
import io.github.chenforegone.encoreticket.model.vo.AdminScreeningDetailVO;
import io.github.chenforegone.encoreticket.model.vo.AdminScreeningSummaryVO;
import io.github.chenforegone.encoreticket.model.vo.ScreeningCreatedVO;
import io.github.chenforegone.encoreticket.model.vo.ScreeningDetailVO;
import io.github.chenforegone.encoreticket.model.vo.ScreeningSeatsVO;
import io.github.chenforegone.encoreticket.model.vo.ScreeningSummaryVO;

/**
 * 场次业务服务，包含状态变更与只读查询。
 *
 * @author c1342
 */
public interface ScreeningService {

    ScreeningCreatedVO create(CreateScreeningRequest request);
    AdminScreeningDetailVO update(String id, UpdateScreeningRequest request);
    AdminScreeningDetailVO onSale(String id, ScreeningVersionRequest request);
    AdminScreeningDetailVO offSale(String id, ScreeningVersionRequest request);
    AdminScreeningDetailVO close(String id, ScreeningVersionRequest request);
    void advanceLifecycle(long id);

    PageResponse<ScreeningSummaryVO> listPublic(PublicScreeningQueryRequest request);
    ScreeningDetailVO getPublic(String screeningId);
    ScreeningSeatsVO getSeats(String screeningId);
    PageResponse<AdminScreeningSummaryVO> listAdmin(AdminScreeningQueryRequest request);
    AdminScreeningDetailVO getAdmin(String screeningId);
}
