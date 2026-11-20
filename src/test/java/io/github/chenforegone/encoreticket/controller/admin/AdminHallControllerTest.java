package io.github.chenforegone.encoreticket.controller.admin;

import io.github.chenforegone.encoreticket.service.HallService;
import io.github.chenforegone.encoreticket.model.vo.HallDetailVO;
import io.github.chenforegone.encoreticket.security.config.SecurityConfig;
import io.github.chenforegone.encoreticket.security.handler.JsonAccessDeniedHandler;
import io.github.chenforegone.encoreticket.security.handler.JsonAuthenticationEntryPoint;
import io.github.chenforegone.encoreticket.security.token.AuthTokenStore;
import io.github.chenforegone.encoreticket.security.token.AuthTokenSession;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import java.util.List;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.any;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 影厅 HTTP 边界、安全链和严格请求格式。
 * @author c1342
 */
@WebMvcTest(AdminHallController.class)
@Import({SecurityConfig.class, JsonAuthenticationEntryPoint.class, JsonAccessDeniedHandler.class})
class AdminHallControllerTest {
    @Autowired MockMvc mvc;
    @MockitoBean HallService service;
    @MockitoBean AuthTokenStore tokens;
    @MockitoBean io.github.chenforegone.encoreticket.mapper.UserMapper users;
    @org.junit.jupiter.api.BeforeEach
    void activeAccount() {
        var user = new io.github.chenforegone.encoreticket.model.entity.User();
        user.setStatus(io.github.chenforegone.encoreticket.model.enums.UserStatus.NORMAL);
        user.setAuthVersion(0L);
        when(users.selectById("9")).thenReturn(user);
    }
    private static final String CREATE = "{\"hallNo\":\"H01\",\"hallName\":\"一号厅\",\"seats\":[{\"rowNumber\":1,\"seatNumber\":3}]}";

    @Test
    void createReturns201WithLocation() throws Exception {
        when(tokens.findSessionByToken("admin")).thenReturn(new AuthTokenSession("9", List.of("ADMIN"), 0L));
        when(service.create(any())).thenReturn(new HallDetailVO("1", "H01", "一号厅", "ENABLED", 0, 1, 1, null, null, null));
        mvc.perform(post("/admin/halls").header("Authorization", "Bearer admin").contentType(MediaType.APPLICATION_JSON).content(CREATE))
                .andExpect(status().isCreated()).andExpect(header().string("Location", "/admin/halls/1"))
                .andExpect(jsonPath("$.data.hallId").value("1"));
    }

    @ParameterizedTest
    @CsvSource({"GET,/admin/halls", "POST,/admin/halls", "GET,/admin/halls/1", "PUT,/admin/halls/1",
            "POST,/admin/halls/1/enable", "POST,/admin/halls/1/disable", "GET,/admin/halls/1/seats",
            "POST,/admin/halls/1/seats", "POST,/admin/halls/1/seats/11/enable", "POST,/admin/halls/1/seats/11/disable"})
    void allRoutesRejectGuestAndUser(String method, String path) throws Exception {
        mvc.perform(request(HttpMethod.valueOf(method), path).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
        when(tokens.findSessionByToken("user")).thenReturn(new AuthTokenSession("9", List.of("USER"), 0L));
        mvc.perform(request(HttpMethod.valueOf(method), path).header("Authorization", "Bearer user").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(service);
    }

    @Test
    void adminListAndRenameAndSeatCommandsReachTheirPublicServices() throws Exception {
        when(tokens.findSessionByToken("admin")).thenReturn(new AuthTokenSession("9", List.of("ADMIN"), 0L));
        mvc.perform(get("/admin/halls").header("Authorization", "Bearer admin").param("hallName", "%_!")
                .param("status", "DISABLED")).andExpect(status().isOk());
        verify(service).list(org.mockito.ArgumentMatchers.argThat(q -> q.hallName().equals("%_!") && q.page() == 1 && q.pageSize() == 20));
        mvc.perform(put("/admin/halls/1").header("Authorization", "Bearer admin").contentType(MediaType.APPLICATION_JSON)
                .content("{\"hallName\":\"新名\",\"version\":2}")).andExpect(status().isOk());
        verify(service).rename("1", new io.github.chenforegone.encoreticket.model.dto.UpdateHallRequest("新名", 2));
        mvc.perform(post("/admin/halls/1/seats/11/enable").header("Authorization", "Bearer admin").contentType(MediaType.APPLICATION_JSON)
                .content("{\"version\":2}")).andExpect(status().isOk());
        verify(service).enableSeat("1", "11", new io.github.chenforegone.encoreticket.model.dto.HallVersionRequest(2));
        mvc.perform(post("/admin/halls/1/seats").header("Authorization", "Bearer admin").contentType(MediaType.APPLICATION_JSON)
                .content("{\"seats\":[{\"rowNumber\":1,\"seatNumber\":4}]}")).andExpect(status().isCreated());
        verify(service).addSeats(org.mockito.ArgumentMatchers.eq("1"), any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"1.1", "\"1\"", "true", "0", "2147483648"})
    void rejectsNonPositiveOrNonIntegerSeatCoordinates(String coordinate) throws Exception {
        when(tokens.findSessionByToken("admin")).thenReturn(new AuthTokenSession("9", List.of("ADMIN"), 0L));
        mvc.perform(post("/admin/halls").header("Authorization", "Bearer admin").contentType(MediaType.APPLICATION_JSON)
                .content(CREATE.replace("\"rowNumber\":1", "\"rowNumber\":" + coordinate)))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    @Test
    void batchSizeLimitsAreOneToFiveHundred() throws Exception {
        when(tokens.findSessionByToken("admin")).thenReturn(new AuthTokenSession("9", List.of("ADMIN"), 0L));
        for (String body : List.of("{\"seats\":[]}", "{\"seats\":[null]}",
                "{\"seats\":[" + String.join(",", java.util.Collections.nCopies(501, "{\"rowNumber\":1,\"seatNumber\":1}")) + "]}")) {
            mvc.perform(post("/admin/halls/1/seats").header("Authorization", "Bearer admin").contentType(MediaType.APPLICATION_JSON)
                    .content(body)).andExpect(status().isBadRequest());
        }
        verifyNoInteractions(service);
    }

    @Test
    void rejectsOverlongAndControlCharacterNamesBeforeService() throws Exception {
        when(tokens.findSessionByToken("admin")).thenReturn(new AuthTokenSession("9", List.of("ADMIN"), 0L));
        for (String name : List.of("x".repeat(65), "bad\\nname")) {
            mvc.perform(post("/admin/halls").header("Authorization", "Bearer admin").contentType(MediaType.APPLICATION_JSON)
                    .content(CREATE.replace("一号厅", name)))
                    .andExpect(status().isBadRequest());
        }
        verifyNoInteractions(service);
    }

    @ParameterizedTest
    @ValueSource(strings = {"-1", "1.1", "\"1\"", "2147483648", "null", "true"})
    void rejectsInvalidVersions(String version) throws Exception {
        when(tokens.findSessionByToken("admin")).thenReturn(new AuthTokenSession("9", List.of("ADMIN"), 0L));
        mvc.perform(post("/admin/halls/1/disable").header("Authorization", "Bearer admin").contentType(MediaType.APPLICATION_JSON)
                .content("{\"version\":" + version + "}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    @ParameterizedTest
    @ValueSource(strings = {"status", "version", "id", "operatorId"})
    void createRejectsProtectedFields(String field) throws Exception {
        when(tokens.findSessionByToken("admin")).thenReturn(new AuthTokenSession("9", List.of("ADMIN"), 0L));
        mvc.perform(post("/admin/halls").header("Authorization", "Bearer admin").contentType(MediaType.APPLICATION_JSON)
                .content(CREATE.substring(0, CREATE.length()-1) + ",\"" + field + "\":0}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }
}
