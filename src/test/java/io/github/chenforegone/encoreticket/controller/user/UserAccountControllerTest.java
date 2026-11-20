package io.github.chenforegone.encoreticket.controller.user;

import io.github.chenforegone.encoreticket.model.dto.RegisterRequest;
import io.github.chenforegone.encoreticket.model.vo.LoginVO;
import io.github.chenforegone.encoreticket.model.vo.NicknameSuggestionVO;
import io.github.chenforegone.encoreticket.model.vo.UserVO;
import io.github.chenforegone.encoreticket.security.token.AuthTokenStore;
import io.github.chenforegone.encoreticket.service.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 用户注册与个人资料接口测试。
 *
 * @author c1342
 */
@WebMvcTest(UserAccountController.class)
@AutoConfigureMockMvc(addFilters = false)
class UserAccountControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private UserService userService;

    @MockitoBean
    private AuthTokenStore authTokenStore;

    @MockitoBean
    private io.github.chenforegone.encoreticket.mapper.UserMapper userMapper;

    @Test
    void registerReturnsCreatedLoginPayload() throws Exception {
        when(userService.register(any(RegisterRequest.class))).thenReturn(login());

        mockMvc.perform(post("/users/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "username": "Alice",
                                  "password": "secret123",
                                  "confirmPassword": "secret123",
                                  "nickname": "Alice",
                                  "email": "",
                                  "phone": ""
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.accessToken").value("opaque-token"))
                .andExpect(jsonPath("$.data.user.username").value("alice"))
                .andExpect(jsonPath("$.data.roles[0]").value("USER"));

        verify(userService).register(any(RegisterRequest.class));
    }

    @Test
    void invalidRegisterPayloadReturnsBadRequestWithoutCallingService() throws Exception {
        mockMvc.perform(post("/users/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"bad","password":"short","confirmPassword":"different"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"));

        org.mockito.Mockito.verifyNoInteractions(userService);
    }

    @Test
    void registerRequiresExplicitNonblankNickname() throws Exception {
        for (String nicknameField : List.of("", ",\"nickname\":\"   \"")) {
            mockMvc.perform(post("/users/register")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"username\":\"Alice\",\"password\":\"secret123\","
                                    + "\"confirmPassword\":\"secret123\"" + nicknameField + "}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"));
        }

        org.mockito.Mockito.verifyNoInteractions(userService);
    }

    @Test
    void nicknameSuggestionReturnsOneCandidate() throws Exception {
        when(userService.generateNicknameSuggestion())
                .thenReturn(new NicknameSuggestionVO("星河旅人"));

        mockMvc.perform(get("/users/nickname/suggestion"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.nickname").value("星河旅人"));

        verify(userService).generateNicknameSuggestion();
    }

    private static LoginVO login() {
        return new LoginVO(
                "opaque-token",
                new UserVO("10001", "alice", "用户123456",
                        "https://cdn.example.com/avatar/default-1.png", null, null,
                        "NORMAL", Instant.parse("2026-09-18T12:00:00Z"),
                        Instant.parse("2026-09-18T12:00:00Z")),
                List.of("USER")
        );
    }
}
