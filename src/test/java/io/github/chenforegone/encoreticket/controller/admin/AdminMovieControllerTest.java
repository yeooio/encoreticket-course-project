package io.github.chenforegone.encoreticket.controller.admin;

import io.github.chenforegone.encoreticket.model.dto.OfflineMovieRequest;
import io.github.chenforegone.encoreticket.model.vo.MovieCreatedVO;
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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 管理员电影写接口测试。
 *
 * @author c1342
 */
@WebMvcTest(AdminMovieController.class)
@AutoConfigureMockMvc(addFilters = false)
class AdminMovieControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private MovieService movieService;

    @MockitoBean
    private AuthTokenStore authTokenStore;

    @MockitoBean
    private io.github.chenforegone.encoreticket.mapper.UserMapper users;

    @ParameterizedTest
    @ValueSource(strings = {"null", "0", "-1", "120.5", "120.0", "1e2", "2147483648", "\"120\"", "true", "[]", "{}"})
    void writesRejectInvalidDurationMinutes(String durationJson) throws Exception {
        when(movieService.create(any())).thenReturn(new MovieCreatedVO("1"));
        String body = """
                {
                  "title": "示例电影",
                  "releaseDate": "2026-10-01",
                  "publishAt": "2099-10-01T10:00:00+08:00",
                  "version": 0,
                  "durationMinutes": %s
                }
                """.formatted(durationJson);

        mockMvc.perform(post("/admin/movies").contentType(APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"));
        mockMvc.perform(put("/admin/movies/1").contentType(APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"));
    }

    @Test
    void updateRequiresDurationMinutes() throws Exception {
        mockMvc.perform(put("/admin/movies/1")
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {
                                  "title": "示例电影",
                                  "releaseDate": "2026-10-01",
                                  "version": 0
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"));
    }

    @Test
    void createRequiresDurationMinutes() throws Exception {
        when(movieService.create(any())).thenReturn(new MovieCreatedVO("1"));

        mockMvc.perform(post("/admin/movies")
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {
                                  "title": "示例电影",
                                  "releaseDate": "2026-10-01",
                                  "publishAt": "2099-10-01T10:00:00+08:00"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"));
    }

    @Test
    void createReturnsMinimalResultAndLocation() throws Exception {
        when(movieService.create(any())).thenReturn(new MovieCreatedVO("1"));

        mockMvc.perform(post("/admin/movies")
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {
                                  "title": "示例电影",
                                  "description": "简介",
                                  "releaseDate": "2026-10-01",
                                  "durationMinutes": 120,
                                  "publishAt": "2099-10-01T10:00:00+08:00"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/admin/movies/1"))
                .andExpect(jsonPath("$.data.id").value("1"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"version\":null}"})
    void publishRequiresVersion(String body) throws Exception {
        mockMvc.perform(post("/admin/movies/1/publish")
                        .contentType(APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"));
    }

    @Test
    void offlineReturnsNoContent() throws Exception {
        mockMvc.perform(post("/admin/movies/1/offline")
                        .contentType(APPLICATION_JSON)
                        .content("{\"version\": 2}"))
                .andExpect(status().isNoContent());

        verify(movieService).offline(eq("1"), any(OfflineMovieRequest.class));
    }
}
