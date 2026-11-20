package io.github.chenforegone.encoreticket.controller.user;

import io.github.chenforegone.encoreticket.common.response.PageResponse;
import io.github.chenforegone.encoreticket.model.vo.MovieSummaryVO;
import io.github.chenforegone.encoreticket.security.token.AuthTokenStore;
import io.github.chenforegone.encoreticket.service.MovieService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 电影 Controller 接口测试。
 *
 * @author c1342
 */
@WebMvcTest(UserMovieController.class)
@AutoConfigureMockMvc(addFilters = false)
class UserMovieControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private MovieService movieService;

    @MockitoBean
    private AuthTokenStore authTokenStore;

    @MockitoBean
    private io.github.chenforegone.encoreticket.mapper.UserMapper users;

    @Test
    void hidesIntegerBindingDetailsBehindControlledFieldMessage() throws Exception {
        mockMvc.perform(get("/movies")
                        .queryParam("page", "secret-invalid-integer")
                        .requestAttr("requestId", "req-binding-1"))
                .andExpect(status().isBadRequest())
                .andExpect(header().string("X-Request-Id", "req-binding-1"))
                .andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"))
                .andExpect(jsonPath("$.message").value("page: 格式不正确"))
                .andExpect(jsonPath("$.requestId").value("req-binding-1"));

        verifyNoInteractions(movieService);
    }

    @ParameterizedTest
    @ValueSource(strings = {"status", "releaseDateFrom", "publishAtFrom"})
    void hidesEnumAndDateBindingDetails(String field) throws Exception {
        mockMvc.perform(get("/movies").queryParam(field, "secret-invalid-value"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"))
                .andExpect(jsonPath("$.message").value(field + ": 格式不正确"));

        verifyNoInteractions(movieService);
    }

    @Test
    void keepsReviewedConstraintMessageForInvalidPage() throws Exception {
        mockMvc.perform(get("/movies").queryParam("page", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"))
                .andExpect(jsonPath("$.message").value("page: 页码必须大于等于 1"));

        verifyNoInteractions(movieService);
    }

    @Test
    void publicListAcceptsTitleAndReleaseDateFilters() throws Exception {
        when(movieService.listPublic(any())).thenReturn(
                new PageResponse<>(List.of(new MovieSummaryVO("1", "流浪地球", null,
                        java.time.LocalDate.of(2026, 10, 1), 120, "ONLINE")), 1, 20, 1));

        mockMvc.perform(get("/movies")
                        .queryParam("title", "流浪")
                        .queryParam("releaseDateFrom", "2026-10-01")
                        .queryParam("releaseDateTo", "2026-10-31"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].id").value("1"))
                .andExpect(jsonPath("$.data.items[0].status").value("ONLINE"));

        verify(movieService).listPublic(any());
    }

}
