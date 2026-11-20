package io.github.chenforegone.encoreticket.security.filter;

import io.github.chenforegone.encoreticket.security.token.AuthTokenSession;
import io.github.chenforegone.encoreticket.security.token.AuthTokenStore;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Redis token filter behavior tests. */
class RedisTokenAuthenticationFilterTest {

    private AuthTokenStore authTokenStore;
    private FilterChain filterChain;
    private RedisTokenAuthenticationFilter filter;

    @BeforeEach
    void setUp() {
        SecurityContextHolder.clearContext();
        authTokenStore = mock(AuthTokenStore.class);
        filterChain = mock(FilterChain.class);
        var users = mock(io.github.chenforegone.encoreticket.mapper.UserMapper.class);
        when(users.selectById("10001")).thenReturn(
                io.github.chenforegone.encoreticket.model.entity.User.register("alice", "hash", "Alice", "avatar"));
        filter = new RedisTokenAuthenticationFilter(authTokenStore, users);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void validTokenWritesUserAndRolesToSecurityContext() throws Exception {
        when(authTokenStore.findSessionByToken("opaque-token"))
                .thenReturn(new AuthTokenSession("10001", List.of("ADMIN", "ROLE_USER"), 0L));

        MockHttpServletRequest request = requestWithAuthorization("Bearer opaque-token");
        filter.doFilter(request, new MockHttpServletResponse(), filterChain);

        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        assertThat(authentication).isNotNull();
        assertThat(authentication.getPrincipal()).isEqualTo("10001");
        assertThat(authentication.getDetails()).isEqualTo("opaque-token");
        assertThat(authentication.getAuthorities())
                .extracting(Object::toString)
                .containsExactlyInAnyOrder("ROLE_ADMIN", "ROLE_USER");
        verify(authTokenStore).findSessionByToken("opaque-token");
        verify(filterChain).doFilter(org.mockito.ArgumentMatchers.eq(request),
                org.mockito.ArgumentMatchers.any(MockHttpServletResponse.class));
    }

    @Test
    void missingMalformedAndUnknownTokensArePassedToAuthorizationLayer() throws Exception {
        MockHttpServletRequest missing = new MockHttpServletRequest();
        filter.doFilter(missing, new MockHttpServletResponse(), filterChain);

        MockHttpServletRequest malformed = requestWithAuthorization("Basic abc");
        filter.doFilter(malformed, new MockHttpServletResponse(), filterChain);

        when(authTokenStore.findSessionByToken("expired-token")).thenReturn(null);
        MockHttpServletRequest expired = requestWithAuthorization("Bearer expired-token");
        filter.doFilter(expired, new MockHttpServletResponse(), filterChain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(authTokenStore).findSessionByToken("expired-token");
        verifyNoMoreInteractions(authTokenStore);
        verifyChainCalledForEachRequest();
    }

    @Test
    void logoutSkipsAuthenticationLookup() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/auth/logout");
        request.addHeader("Authorization", "Bearer current-token");

        filter.doFilter(request, new MockHttpServletResponse(), filterChain);

        org.mockito.Mockito.verifyNoInteractions(authTokenStore);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void redisFailureIsNotConvertedToAnonymousAuthentication() {
        when(authTokenStore.findSessionByToken("opaque-token"))
                .thenThrow(new IllegalStateException("redis unavailable"));

        assertThatThrownBy(() -> filter.doFilter(
                requestWithAuthorization("Bearer opaque-token"),
                new MockHttpServletResponse(),
                filterChain
        )).isInstanceOf(IllegalStateException.class)
                .hasMessage("redis unavailable");

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    private MockHttpServletRequest requestWithAuthorization(String value) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", value);
        return request;
    }

    private void verifyChainCalledForEachRequest() throws Exception {
        verify(filterChain, org.mockito.Mockito.atLeast(3))
                .doFilter(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }
}
