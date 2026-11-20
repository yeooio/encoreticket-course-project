package io.github.chenforegone.encoreticket.controller;

import io.github.chenforegone.encoreticket.controller.user.UserTicketController;

import io.github.chenforegone.encoreticket.common.exception.ResourceNotFoundException;
import io.github.chenforegone.encoreticket.common.response.PageResponse;
import io.github.chenforegone.encoreticket.converter.TicketConverter;
import io.github.chenforegone.encoreticket.mapper.TicketMapper;
import io.github.chenforegone.encoreticket.model.dto.TicketQueryRequest;
import io.github.chenforegone.encoreticket.model.query.TicketProjection;
import io.github.chenforegone.encoreticket.model.vo.TicketStatusVO;
import io.github.chenforegone.encoreticket.security.config.SecurityConfig;
import io.github.chenforegone.encoreticket.security.token.AuthTokenSession;
import io.github.chenforegone.encoreticket.security.token.AuthTokenStore;
import io.github.chenforegone.encoreticket.service.TicketService;
import io.github.chenforegone.encoreticket.service.rules.TicketStatusResolver;
import io.github.chenforegone.encoreticket.service.impl.TicketServiceImpl;
import jakarta.validation.Validator;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.aMapWithSize;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 票券 HTTP 契约及真实安全过滤器链的认证回归。
 *
 * @author c1342
 */
@WebMvcTest(UserTicketController.class)
@Import({SecurityConfig.class,
        io.github.chenforegone.encoreticket.security.handler.JsonAuthenticationEntryPoint.class,
        io.github.chenforegone.encoreticket.security.handler.JsonAccessDeniedHandler.class})
class TicketControllerTest {
    @Autowired MockMvc mvc;
    @Autowired Validator validator;
    @MockitoBean TicketService service;
    @MockitoBean AuthTokenStore tokens;

    @MockitoBean io.github.chenforegone.encoreticket.mapper.UserMapper users;

    private void login(String role) {
        when(users.selectById("7")).thenReturn(io.github.chenforegone.encoreticket.model.entity.User.register("test", "hash", "测试", "avatar"));
        when(tokens.findSessionByToken("valid-token")).thenReturn(new AuthTokenSession("7", List.of(role), 0L));
    }

    @Test
    void allThreeRoutesRejectMissingMalformedAndExpiredTokens() throws Exception {
        for (String path : List.of("/tickets", "/tickets/1", "/tickets/1/status")) {
            for (String authorization : List.of("", "Basic invalid", "Bearer expired-token")) {
                mvc.perform(get(path).header("Authorization", authorization))
                        .andExpect(status().isUnauthorized())
                        .andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
                        .andExpect(header().exists("X-Request-Id"))
                        .andExpect(jsonPath("$.requestId").isNotEmpty());
            }
        }
        verifyNoInteractions(service);
    }

    @Test
    void acceptsTrustedStringPrincipalForUserStaffAndAdminIgnoringClientOwner() throws Exception {
        when(service.list(eq("7"), any())).thenReturn(new PageResponse<>(List.of(), 1, 10, 0));
        when(service.getStatus("7", "1")).thenReturn(new TicketStatusVO("VALID"));
        for (String role : List.of("USER", "ADMIN", "CHECKIN_STAFF")) {
            login(role);
            mvc.perform(get("/tickets").header("Authorization", "Bearer valid-token").param("userId", "99"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.page").value(1))
                    .andExpect(jsonPath("$.data.pageSize").value(10))
                    .andExpect(jsonPath("$.data.total").value(0))
                    .andExpect(jsonPath("$.data.items").isEmpty())
                    .andExpect(header().exists("X-Request-Id"));
            mvc.perform(get("/tickets/1/status").header("Authorization", "Bearer valid-token"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.data", aMapWithSize(1)))
                    .andExpect(jsonPath("$.data.status").value("VALID"));
        }
        verify(service, times(3)).list(eq("7"), argThat(q -> q.getPage() == 1 && q.getPageSize() == 10));
        verify(service, times(3)).getStatus("7", "1");
    }

    @Test
    void validatesPaginationTypesLengthsAndStatusBeforeService() throws Exception {
        login("USER");
        for (String query : List.of("page=0", "page=1.5", "page=2147483648", "pageSize=0", "pageSize=51",
                "status=UNKNOWN", "status=valid", "movieTitle=" + "m".repeat(256), "orderNo=" + "o".repeat(33))) {
            mvc.perform(get("/tickets?" + query).header("Authorization", "Bearer valid-token"))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"));
        }
        verifyNoInteractions(service);
    }

    @Test
    void bindsAndNormalizesFiltersAndAcceptsPageSizeLimits() throws Exception {
        login("USER");
        when(service.list(eq("7"), any())).thenAnswer(invocation -> {
            TicketQueryRequest request = invocation.getArgument(1);
            return new PageResponse<>(List.of(), request.getPage(), request.getPageSize(), 0);
        });
        for (int size : List.of(1, 50)) {
            mvc.perform(get("/tickets").header("Authorization", "Bearer valid-token")
                            .param("pageSize", Integer.toString(size)).param("status", " REFUNDING ")
                            .param("movieTitle", "  电影%_!\\  ").param("orderNo", "  ORDER-1  "))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.data.pageSize").value(size));
        }
        verify(service, times(2)).list(eq("7"), argThat(q -> q.getStatus().equals("REFUNDING")
                && q.getMovieTitle().equals("电影%_!\\") && q.getOrderNo().equals("ORDER-1")));
    }

    @Test
    void fullCodeOnlyAppearsInDetailAndStatusIsLightweight() throws Exception {
        login("USER");
        TicketConverter converter = new TicketConverter(new TicketStatusResolver());
        LocalDateTime now = LocalDateTime.parse("2026-10-19T12:00:00");
        TicketProjection ticket = new TicketProjection(1L, 2L, 3L, 7L, 4L, 5L, 6L, 8L, 9L,
                "PRIVATE-CODE", "ORDER-1", "电影", "一号厅", 0, 1, 2, 3,
                now, now.plusHours(2), new BigDecimal("88.00"), null, Instant.parse("2026-10-19T04:00:00Z"));
        when(service.list(eq("7"), any())).thenReturn(new PageResponse<>(List.of(converter.toSummary(ticket, now)), 1, 10, 1));
        when(service.getDetail("7", "1")).thenReturn(converter.toDetail(ticket, now));
        when(service.getStatus("7", "1")).thenReturn(converter.toStatus(ticket, now));
        mvc.perform(get("/tickets").header("Authorization", "Bearer valid-token"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.items[0].id").value("1"))
                .andExpect(jsonPath("$.data.items[0].ticketCode").doesNotExist());
        mvc.perform(get("/tickets/1").header("Authorization", "Bearer valid-token"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.ticketCode").value("PRIVATE-CODE"))
                .andExpect(jsonPath("$.data.unitPrice").value("88.00"))
                .andExpect(jsonPath("$.data.currency").value("CNY"))
                .andExpect(jsonPath("$.data.verifiedAt").isEmpty());
        mvc.perform(get("/tickets/1/status").header("Authorization", "Bearer valid-token"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data", aMapWithSize(1)))
                .andExpect(jsonPath("$.data.status").value("VALID"));
    }

    @Test
    void strictPathIdsAreRejectedByRealServiceForBothRoutes() throws Exception {
        login("USER");
        TicketMapper mapper = mock(TicketMapper.class);
        var real = new TicketServiceImpl(mapper, new TicketConverter(new TicketStatusResolver()), validator,
                java.time.Clock.fixed(Instant.parse("2026-10-19T04:00:00Z"), ZoneOffset.UTC));
        when(service.getDetail(anyString(), anyString())).thenAnswer(i -> real.getDetail(i.getArgument(0), i.getArgument(1)));
        when(service.getStatus(anyString(), anyString())).thenAnswer(i -> real.getStatus(i.getArgument(0), i.getArgument(1)));
        for (String id : List.of("0", "-1", "+1", "01", "1.0", "1e2", "abc", "9223372036854775808", " 1", "1 ")) {
            for (String suffix : List.of("", "/status")) {
                mvc.perform(get("/tickets/{id}" + suffix, id).header("Authorization", "Bearer valid-token"))
                        .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"));
            }
        }
        verifyNoInteractions(mapper);
    }

    @Test
    void authenticatedAdminStillCannotReadAnotherOwnersTicket() throws Exception {
        login("ADMIN");
        TicketMapper mapper = mock(TicketMapper.class);
        var real = new TicketServiceImpl(mapper, new TicketConverter(new TicketStatusResolver()), validator,
                java.time.Clock.fixed(Instant.parse("2026-10-19T04:00:00Z"), ZoneOffset.UTC));
        when(service.getDetail(anyString(), anyString())).thenAnswer(i -> real.getDetail(i.getArgument(0), i.getArgument(1)));
        when(service.getStatus(anyString(), anyString())).thenAnswer(i -> real.getStatus(i.getArgument(0), i.getArgument(1)));
        for (String suffix : List.of("", "/status")) {
            mvc.perform(get("/tickets/99" + suffix).param("userId", "99")
                            .header("Authorization", "Bearer valid-token"))
                    .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
        }
        verify(mapper, times(2)).selectOwned(99, 7);
        verifyNoMoreInteractions(mapper);
    }

    @Test
    void preservesNotFoundAndInternalErrorsWithoutSensitiveDetails() throws Exception {
        login("USER");
        when(service.getDetail("7", "1")).thenThrow(new ResourceNotFoundException());
        when(service.getDetail("7", "2")).thenThrow(new ResourceNotFoundException());
        for (String id : List.of("1", "2")) {
            mvc.perform(get("/tickets/" + id).header("Authorization", "Bearer valid-token"))
                    .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
        }
        when(service.getStatus("7", "1")).thenThrow(new IllegalStateException("PRIVATE-CODE SQL"));
        mvc.perform(get("/tickets/1/status").header("Authorization", "Bearer valid-token"))
                .andExpect(status().isInternalServerError()).andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("PRIVATE-CODE"))))
                .andExpect(header().exists("X-Request-Id"));
    }
}
