package io.github.chenforegone.encoreticket.controller.admin;

import io.github.chenforegone.encoreticket.model.vo.ScreeningCreatedVO;
import io.github.chenforegone.encoreticket.security.config.SecurityConfig;
import io.github.chenforegone.encoreticket.security.handler.JsonAccessDeniedHandler;
import io.github.chenforegone.encoreticket.security.handler.JsonAuthenticationEntryPoint;
import io.github.chenforegone.encoreticket.security.token.AuthTokenSession;
import io.github.chenforegone.encoreticket.security.token.AuthTokenStore;
import io.github.chenforegone.encoreticket.service.ScreeningService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 管理场次的实际安全链、JSON 输入及路由契约。
 *
 * @author c1342
 */
@WebMvcTest(AdminScreeningController.class)
@Import({SecurityConfig.class, JsonAuthenticationEntryPoint.class, JsonAccessDeniedHandler.class})
class AdminScreeningControllerTest {
    private static final String CREATE = """
            {"movieId":"1","hallId":"2","price":"50.00",
             "startTime":"2026-10-01T20:00:00+08:00","saleStartTime":"2026-10-01T10:00:00+08:00"}
            """;
    @Autowired private MockMvc mvc;
    @MockitoBean private ScreeningService commands;
    @MockitoBean private AuthTokenStore tokens;
    @MockitoBean private io.github.chenforegone.encoreticket.mapper.UserMapper users;

    @org.junit.jupiter.api.BeforeEach
    void activeAccount() {
        when(users.selectById("9")).thenReturn(io.github.chenforegone.encoreticket.model.entity.User.register("test", "hash", "测试", "avatar"));
    }

    @ParameterizedTest
    @CsvSource({"POST,/admin/screenings", "GET,/admin/screenings", "GET,/admin/screenings/1",
            "PUT,/admin/screenings/1", "POST,/admin/screenings/1/on-sale",
            "POST,/admin/screenings/1/off-sale", "POST,/admin/screenings/1/close"})
    void allAdminRoutesRejectAnonymous(String method, String path) throws Exception {
        mvc.perform(request(HttpMethod.valueOf(method), path).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        verifyNoInteractions(commands);
    }

    @ParameterizedTest
    @CsvSource({"POST,/admin/screenings", "GET,/admin/screenings", "GET,/admin/screenings/1",
            "PUT,/admin/screenings/1", "POST,/admin/screenings/1/on-sale",
            "POST,/admin/screenings/1/off-sale", "POST,/admin/screenings/1/close"})
    void allAdminRoutesRejectUser(String method, String path) throws Exception {
        when(tokens.findSessionByToken("user")).thenReturn(new AuthTokenSession("9", List.of("USER"), 0L));
        mvc.perform(request(HttpMethod.valueOf(method), path).header("Authorization", "Bearer user")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"));
        verifyNoInteractions(commands);
    }

    @Test
    void createsWithoutIdempotencyKeyAndReturnsLocation() throws Exception {
        admin();
        when(commands.create(any())).thenReturn(new ScreeningCreatedVO("1001"));
        mvc.perform(post("/admin/screenings").header("Authorization", "Bearer admin")
                        .contentType(MediaType.APPLICATION_JSON).content(CREATE))
                .andExpect(status().isCreated()).andExpect(header().string("Location", "/admin/screenings/1001"))
                .andExpect(jsonPath("$.data.id").value("1001"));
        verify(commands).create(any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"status", "endTime", "saleEndTime", "operatorId", "durationMinutesSnapshot", "unexpected"})
    void rejectsProtectedOrUnknownFields(String field) throws Exception {
        admin();
        String body = CREATE.strip().replaceFirst("}$", ",\"" + field + "\":\"x\"}");
        mvc.perform(post("/admin/screenings").header("Authorization", "Bearer admin")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"));
        verifyNoInteractions(commands);
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "movieId|1", "hallId|2", "price|50.00", "movieId|\"9223372036854775808\"",
            "price|\"0\"", "price|\"0.001\"", "price|\"100000000\""})
    void rejectsInvalidIdentifiersAndMoney(String field, String literal) throws Exception {
        admin();
        String body = CREATE.replaceFirst("\"" + field + "\":\"[^\"]*\"", "\"" + field + "\":" + literal);
        mvc.perform(post("/admin/screenings").header("Authorization", "Bearer admin")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"));
        verifyNoInteractions(commands);
    }

    @ParameterizedTest
    @ValueSource(strings = {"-1", "1.1", "\"1\"", "2147483648", "null"})
    void rejectsInvalidVersions(String version) throws Exception {
        admin();
        mvc.perform(post("/admin/screenings/1/off-sale").header("Authorization", "Bearer admin")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"version\":" + version + "}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"));
        verifyNoInteractions(commands);
    }

    @ParameterizedTest
    @ValueSource(strings = {"on-sale", "off-sale", "close"})
    void routesVersionedCommands(String action) throws Exception {
        admin();
        mvc.perform(post("/admin/screenings/1/" + action).header("Authorization", "Bearer admin")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"version\":3}"))
                .andExpect(status().isOk());
        switch (action) {
            case "on-sale" -> verify(commands).onSale(eq("1"), any());
            case "off-sale" -> verify(commands).offSale(eq("1"), any());
            default -> verify(commands).close(eq("1"), any());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"1", "true", "\"1\"", "\"UNKNOWN\""})
    void rejectsNonTextualOrUnknownSaleMode(String mode) throws Exception {
        admin();
        String body = CREATE.strip().replaceFirst("}$", ",\"saleMode\":" + mode + "}");
        mvc.perform(post("/admin/screenings").header("Authorization", "Bearer admin")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(commands);
    }

    @Test
    void rejectsEpochNumberInsteadOfRfc3339BusinessTime() throws Exception {
        admin();
        String body = CREATE.replace("\"2026-10-01T20:00:00+08:00\"", "1790856000");
        mvc.perform(post("/admin/screenings").header("Authorization", "Bearer admin")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(commands);
    }
    @Test
    void adminCanReadListAndDetailAndUpdateWaitingScreening() throws Exception {
        admin();
        mvc.perform(request(HttpMethod.GET, "/admin/screenings").header("Authorization", "Bearer admin"))
                .andExpect(status().isOk());
        mvc.perform(request(HttpMethod.GET, "/admin/screenings/1").header("Authorization", "Bearer admin"))
                .andExpect(status().isOk());
        mvc.perform(request(HttpMethod.PUT, "/admin/screenings/1").header("Authorization", "Bearer admin")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CREATE.strip().replaceFirst("}$", ",\"version\":0}")))
                .andExpect(status().isOk());
        verify(commands).listAdmin(any());
        verify(commands).getAdmin("1");
        verify(commands).update(eq("1"), any());
    }
    private void admin() {
        when(tokens.findSessionByToken("admin")).thenReturn(new AuthTokenSession("9", List.of("ADMIN"), 0L));
    }
}
