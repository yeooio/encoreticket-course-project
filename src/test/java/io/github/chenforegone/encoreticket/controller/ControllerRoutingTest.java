package io.github.chenforegone.encoreticket.controller;

import io.github.chenforegone.encoreticket.common.response.PageResponse;
import io.github.chenforegone.encoreticket.model.vo.MovieSummaryVO;
import io.github.chenforegone.encoreticket.model.vo.UserVO;
import io.github.chenforegone.encoreticket.security.config.SecurityConfig;
import io.github.chenforegone.encoreticket.security.handler.JsonAccessDeniedHandler;
import io.github.chenforegone.encoreticket.security.handler.JsonAuthenticationEntryPoint;
import io.github.chenforegone.encoreticket.security.token.AuthTokenSession;
import io.github.chenforegone.encoreticket.security.token.AuthTokenStore;
import io.github.chenforegone.encoreticket.service.AuthService;
import io.github.chenforegone.encoreticket.service.MovieService;
import io.github.chenforegone.encoreticket.service.ScreeningService;
import io.github.chenforegone.encoreticket.service.UserService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 同时加载全部 Controller，验证两端路由和真实安全链的访问边界。
 *
 * @author c1342
 */
@WebMvcTest
@Import({SecurityConfig.class, JsonAuthenticationEntryPoint.class, JsonAccessDeniedHandler.class})
class ControllerRoutingTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AuthService authService;

    @MockitoBean
    private UserService userService;

    @MockitoBean
    private MovieService movieService;

    @MockitoBean
    private ScreeningService screeningService;


    @MockitoBean
    private io.github.chenforegone.encoreticket.service.OrderService orderService;

    @MockitoBean
    private io.github.chenforegone.encoreticket.service.PaymentService paymentService;

    @MockitoBean
    private io.github.chenforegone.encoreticket.service.RefundService refundService;

    @MockitoBean
    private io.github.chenforegone.encoreticket.service.TicketService ticketService;

    @MockitoBean
    private io.github.chenforegone.encoreticket.service.TicketVerificationService ticketVerificationService;

    @MockitoBean
    private io.github.chenforegone.encoreticket.simulation.payment.PaymentSimulationService paymentSimulationService;

    @MockitoBean
    private io.github.chenforegone.encoreticket.service.PaymentChannelResultHandler paymentChannelResultHandler;

    @MockitoBean
    private AuthTokenStore authTokenStore;

    @MockitoBean
    private io.github.chenforegone.encoreticket.mapper.UserMapper users;

    @MockitoBean
    private io.github.chenforegone.encoreticket.service.HallService hallService;

    @org.junit.jupiter.api.BeforeEach
    void activeAccounts() {
        when(users.selectById("20001")).thenReturn(io.github.chenforegone.encoreticket.model.entity.User.register("test", "hash", "测试", "avatar"));
        when(users.selectById("30001")).thenReturn(io.github.chenforegone.encoreticket.model.entity.User.register("admin", "hash", "测试", "avatar"));
    }

    @Test
    void anonymousUserCanReadPublicMovies() throws Exception {
        when(movieService.listPublic(any())).thenReturn(moviePage("ONLINE"));

        mockMvc.perform(get("/movies"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].id").value("10001"))
                .andExpect(jsonPath("$.data.items[0].status").value("ONLINE"));

        verify(movieService).listPublic(any());
        verify(movieService, never()).listAdmin(any());
    }

    @Test
    void anonymousUserCannotReadAdminMovies() throws Exception {
        mockMvc.perform(get("/admin/movies"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
                .andExpect(header().exists("X-Request-Id"));

        verifyNoInteractions(movieService);
    }

    @ParameterizedTest
    @ValueSource(strings = {"USER", "CHECKIN_STAFF"})
    void nonAdminRoleCannotReadAdminMovies(String role) throws Exception {
        when(authTokenStore.findSessionByToken("non-admin-token"))
                .thenReturn(new AuthTokenSession("20001", List.of(role), 0L));

        mockMvc.perform(get("/admin/movies")
                        .header("Authorization", "Bearer non-admin-token"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"))
                .andExpect(header().exists("X-Request-Id"));

        verifyNoInteractions(movieService);
    }

    @Test
    void adminReadsMoviesThroughAdminQuery() throws Exception {
        when(authTokenStore.findSessionByToken("admin-token"))
                .thenReturn(new AuthTokenSession("30001", List.of("ADMIN"), 0L));
        when(movieService.listAdmin(any())).thenReturn(moviePage("OFFLINE"));

        mockMvc.perform(get("/admin/movies")
                        .header("Authorization", "Bearer admin-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].id").value("10001"))
                .andExpect(jsonPath("$.data.items[0].status").value("OFFLINE"));

        verify(movieService).listAdmin(any());
        verify(movieService, never()).listPublic(any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"GUEST", "USER", "CHECKIN_STAFF", "ADMIN"})
    void publicationRequiresAdminRole(String role) throws Exception {
        var request = org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .post("/admin/movies/1/publish")
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content("{\"version\":0}");
        if (!role.equals("GUEST")) {
            when(authTokenStore.findSessionByToken("publish-token"))
                    .thenReturn(new AuthTokenSession("30001", List.of(role), 0L));
            request.header("Authorization", "Bearer publish-token");
        }
        int expectedStatus = role.equals("GUEST") ? 401 : role.equals("ADMIN") ? 204 : 403;
        var result = mockMvc.perform(request)
                .andExpect(status().is(expectedStatus))
                .andExpect(header().exists("X-Request-Id"));
        if (!role.equals("ADMIN")) {
            result.andExpect(jsonPath("$.code").value(role.equals("GUEST") ? "UNAUTHORIZED" : "FORBIDDEN"));
            verifyNoInteractions(movieService);
        }
    }

    @Test
    void adminUsesSharedProfileEndpointWithAuthenticatedIdentity() throws Exception {
        when(authTokenStore.findSessionByToken("admin-token"))
                .thenReturn(new AuthTokenSession("30001", List.of("ADMIN"), 0L));
        Instant createdAt = Instant.parse("2026-09-18T12:00:00Z");
        when(userService.getCurrentUser("30001")).thenReturn(new UserVO(
                "30001", "admin", "影院管理员", null, null, null,
                "NORMAL", createdAt, createdAt));

        mockMvc.perform(get("/users/me")
                        .header("Authorization", "Bearer admin-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value("30001"))
                .andExpect(jsonPath("$.data.username").value("admin"));

        verify(userService).getCurrentUser("30001");
    }

    private static PageResponse<MovieSummaryVO> moviePage(String status) {
        return new PageResponse<>(List.of(new MovieSummaryVO(
                "10001", "示例电影", null, LocalDate.of(2026, 10, 1), 120, status)), 1, 20, 1);
    }
}
