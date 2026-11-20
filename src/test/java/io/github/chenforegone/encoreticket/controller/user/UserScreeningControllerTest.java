package io.github.chenforegone.encoreticket.controller.user;

import io.github.chenforegone.encoreticket.common.response.PageResponse;
import io.github.chenforegone.encoreticket.model.vo.ScreeningSeatsVO;
import io.github.chenforegone.encoreticket.model.vo.ScreeningSeatVO;
import io.github.chenforegone.encoreticket.security.config.SecurityConfig;
import io.github.chenforegone.encoreticket.security.handler.JsonAccessDeniedHandler;
import io.github.chenforegone.encoreticket.security.handler.JsonAuthenticationEntryPoint;
import io.github.chenforegone.encoreticket.security.token.AuthTokenStore;
import io.github.chenforegone.encoreticket.service.ScreeningService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.OffsetDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 公开场次接口、分页及座位隐私响应契约。
 *
 * @author c1342
 */
@WebMvcTest(UserScreeningController.class)
@Import({SecurityConfig.class, JsonAuthenticationEntryPoint.class, JsonAccessDeniedHandler.class})
class UserScreeningControllerTest {
    @Autowired private MockMvc mockMvc;
    @MockitoBean private ScreeningService screeningService;
    @MockitoBean private AuthTokenStore authTokenStore;
    @MockitoBean private io.github.chenforegone.encoreticket.mapper.UserMapper users;

    @Test
    void listsScreeningsWithoutRequiredMovieFilterAsPage() throws Exception {
        when(screeningService.listPublic(any())).thenReturn(new PageResponse<>(List.of(), 1, 20, 0));
        mockMvc.perform(get("/screenings"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items").isEmpty())
                .andExpect(jsonPath("$.data.page").value(1))
                .andExpect(jsonPath("$.data.pageSize").value(20))
                .andExpect(jsonPath("$.data.total").value(0));
        verify(screeningService).listPublic(any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"/screenings", "/screenings/1", "/screenings/1/seats"})
    void invalidTokenDoesNotBlockPublicRead(String path) throws Exception {
        mockMvc.perform(get(path).header("Authorization", "Bearer expired"))
                .andExpect(status().isOk());
    }

    @Test
    void heldSeatShowsOnlyOccupancyAndLayout() throws Exception {
        when(screeningService.getSeats("1")).thenReturn(new ScreeningSeatsVO("1", "ON_SALE", true,
                OffsetDateTime.parse("2026-10-01T10:00:00+08:00"),
                List.of(new ScreeningSeatVO("3", "2", 1, 1, "HELD"))));
        mockMvc.perform(get("/screenings/1/seats"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.seats[0].status").value("HELD"))
                .andExpect(jsonPath("$.data.seats[0].orderId").doesNotExist())
                .andExpect(jsonPath("$.data.seats[0].userId").doesNotExist())
                .andExpect(jsonPath("$.data.seats[0].expiresAt").doesNotExist());
    }

    @ParameterizedTest
    @ValueSource(strings = {"page=0", "pageSize=101", "page=1.5", "pageSize=abc", "screeningDate=2026-13-01"})
    void rejectsMalformedPagingOrDate(String query) throws Exception {
        String[] parts = query.split("=");
        mockMvc.perform(get("/screenings").queryParam(parts[0], parts[1]))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"));
        verifyNoInteractions(screeningService);
    }
}
