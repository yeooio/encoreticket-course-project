package io.github.chenforegone.encoreticket.controller.user;

import io.github.chenforegone.encoreticket.common.exception.AuthenticationFailureException;
import io.github.chenforegone.encoreticket.common.exception.ForbiddenException;
import io.github.chenforegone.encoreticket.model.vo.LoginVO;
import io.github.chenforegone.encoreticket.model.vo.SessionVO;
import io.github.chenforegone.encoreticket.model.vo.UserVO;
import io.github.chenforegone.encoreticket.security.token.AuthTokenStore;
import io.github.chenforegone.encoreticket.service.AuthService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 认证接口测试。
 *
 * @author c1342
 */
@WebMvcTest(UserAuthController.class)
@AutoConfigureMockMvc(addFilters = false)
class UserAuthControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AuthService authService;

    @MockitoBean
    private AuthTokenStore authTokenStore;

    @MockitoBean
    private io.github.chenforegone.encoreticket.mapper.UserMapper userMapper;

    @Test
    void loginReturnsOkLoginPayload() throws Exception {
        when(authService.login(any())).thenReturn(login());

        mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "username": "Alice",
                                  "password": "secret123"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.accessToken").value("opaque-token"))
                .andExpect(jsonPath("$.data.user.username").value("alice"))
                .andExpect(jsonPath("$.data.roles[0]").value("USER"));

        verify(authService).login(any());
    }

    @Test
    void invalidLoginPayloadReturnsBadRequestWithoutCallingService() throws Exception {
        mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"\",\"password\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"));

        verifyNoInteractions(authService);
    }

    @Test
    void invalidCredentialsReturnUnauthorizedWithSafeMessage() throws Exception {
        when(authService.login(any())).thenThrow(new AuthenticationFailureException());

        mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "username": "alice",
                                  "password": "wrong123"
                                }
                                """))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
                .andExpect(jsonPath("$.message").value("用户名或密码错误"));
    }

    @Test
    void disabledUserReturnsForbidden() throws Exception {
        when(authService.login(any())).thenThrow(new ForbiddenException());

        mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "username": "alice",
                                  "password": "secret123"
                                }
                                """))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void logoutCallsServiceAndReturnsOk() throws Exception {
        mockMvc.perform(post("/auth/logout"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").doesNotExist());

        verify(authService).logout(null);
    }

    @Test
    void meReturnsSessionInfo() throws Exception {
        when(authService.me()).thenReturn(session());

        mockMvc.perform(get("/auth/me"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.user.username").value("alice"))
                .andExpect(jsonPath("$.data.roles[0]").value("USER"));

        verify(authService).me();
    }

    private static LoginVO login() {
        return new LoginVO(
                "opaque-token",
                new UserVO("10001", "alice", "Alice", "avatar", null, null,
                        "NORMAL", Instant.parse("2026-09-19T12:00:00Z"),
                        Instant.parse("2026-09-19T12:00:00Z")),
                List.of("USER")
        );
    }

    private static SessionVO session() {
        return new SessionVO(
                new UserVO("10001", "alice", "Alice", "avatar", null, null,
                        "NORMAL", Instant.parse("2026-09-19T12:00:00Z"),
                        Instant.parse("2026-09-19T12:00:00Z")),
                List.of("USER")
        );
    }
}
