package io.github.chenforegone.encoreticket.service;

import io.github.chenforegone.encoreticket.common.exception.AuthenticationFailureException;
import io.github.chenforegone.encoreticket.common.exception.ForbiddenException;
import io.github.chenforegone.encoreticket.common.exception.InvalidRequestException;
import io.github.chenforegone.encoreticket.common.exception.ResourceNotFoundException;
import io.github.chenforegone.encoreticket.converter.UserConverter;
import io.github.chenforegone.encoreticket.mapper.UserMapper;
import io.github.chenforegone.encoreticket.mapper.UserRoleMapper;
import io.github.chenforegone.encoreticket.model.dto.LoginRequest;
import io.github.chenforegone.encoreticket.model.entity.User;
import io.github.chenforegone.encoreticket.model.enums.UserStatus;
import io.github.chenforegone.encoreticket.model.vo.LoginVO;
import io.github.chenforegone.encoreticket.model.vo.SessionVO;
import io.github.chenforegone.encoreticket.security.token.AuthTokenStore;
import io.github.chenforegone.encoreticket.security.token.TokenGenerator;
import io.github.chenforegone.encoreticket.service.impl.AuthServiceImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 认证业务测试。
 *
 * @author c1342
 */
class AuthServiceImplTest {

    private UserMapper userMapper;
    private UserRoleMapper userRoleMapper;
    private PasswordEncoder passwordEncoder;
    private TokenGenerator tokenGenerator;
    private AuthTokenStore authTokenStore;
    private AuthServiceImpl service;

    @BeforeEach
    void setUp() {
        userMapper = mock(UserMapper.class);
        userRoleMapper = mock(UserRoleMapper.class);
        passwordEncoder = mock(PasswordEncoder.class);
        tokenGenerator = mock(TokenGenerator.class);
        authTokenStore = mock(AuthTokenStore.class);
        service = new AuthServiceImpl(
                userMapper,
                userRoleMapper,
                new UserConverter(),
                passwordEncoder,
                tokenGenerator,
                authTokenStore
        );
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void logsInWithNormalizedUsernameAndRoleSnapshot() {
        User user = normalUser();

        when(userMapper.selectOne(any())).thenReturn(user);
        when(passwordEncoder.matches("secret123", user.getPassword())).thenReturn(true);
        when(userRoleMapper.selectRoleCodesByUserId(10001L)).thenReturn(List.of("USER"));
        when(tokenGenerator.generate()).thenReturn("opaque-token");

        LoginVO result = service.login(new LoginRequest("Alice", "secret123"));

        assertThat(result.accessToken()).isEqualTo("opaque-token");
        assertThat(result.user().id()).isEqualTo("10001");
        assertThat(result.user().username()).isEqualTo("alice");
        assertThat(result.roles()).containsExactly("USER");
        verify(authTokenStore).saveToken("10001", List.of("USER"), "opaque-token", 0L);
    }

    @Test
    void issuesUsersReadRolesAndVersion() {
        User user = normalUser();
        user.setAuthVersion(8L);
        when(userMapper.selectOne(any())).thenReturn(user);
        when(passwordEncoder.matches("secret123", user.getPassword())).thenReturn(true);
        when(userRoleMapper.selectRoleCodesByUserId(10001L)).thenReturn(List.of("USER", "CHECKIN_STAFF"));
        when(tokenGenerator.generate()).thenReturn("versioned-token");
        assertThat(service.login(new LoginRequest("alice", "secret123")).roles()).containsExactly("USER", "CHECKIN_STAFF");
        verify(authTokenStore).saveToken("10001", List.of("USER", "CHECKIN_STAFF"), "versioned-token", 8L);
    }

    @Test
    void concurrentPasswordChangeDoesNotUpgradeVersionOfEarlierCredentialSnapshot() {
        User snapshot = normalUser();
        snapshot.setAuthVersion(7L);
        when(userMapper.selectOne(any())).thenReturn(snapshot);
        when(passwordEncoder.matches("secret123", snapshot.getPassword())).thenReturn(true);
        when(userRoleMapper.selectRoleCodesByUserId(10001L)).thenReturn(List.of("USER"));
        when(tokenGenerator.generate()).thenReturn("stale-version-token");

        assertThat(service.login(new LoginRequest("alice", "secret123")).accessToken())
                .isEqualTo("stale-version-token");
        verify(authTokenStore).saveToken("10001", List.of("USER"), "stale-version-token", 7L);
    }

    @Test
    void rejectsWrongPasswordWithoutIssuingToken() {
        User user = normalUser();
        when(userMapper.selectOne(any())).thenReturn(user);
        when(passwordEncoder.matches("wrong123", user.getPassword())).thenReturn(false);

        assertThatThrownBy(() -> service.login(new LoginRequest("alice", "wrong123")))
                .isInstanceOf(AuthenticationFailureException.class)
                .hasMessage("用户名或密码错误");

        verify(tokenGenerator, never()).generate();
        verify(authTokenStore, never()).saveToken(anyString(), any(), anyString(), any());
    }

    @Test
    void rejectsUnknownUserWithSameCredentialFailure() {
        when(userMapper.selectOne(any())).thenReturn(null);
        when(passwordEncoder.matches(eq("secret123"), anyString())).thenReturn(false);

        assertThatThrownBy(() -> service.login(new LoginRequest("alice", "secret123")))
                .isInstanceOf(AuthenticationFailureException.class)
                .hasMessage("用户名或密码错误");

        verify(tokenGenerator, never()).generate();
        verify(authTokenStore, never()).saveToken(anyString(), any(), anyString(), any());
    }

    @Test
    void rejectsDisabledUserWithForbiddenAfterCredentialCheck() {
        User user = normalUser();
        user.setStatus(UserStatus.DISABLED);
        when(userMapper.selectOne(any())).thenReturn(user);
        when(passwordEncoder.matches("secret123", user.getPassword())).thenReturn(true);

        assertThatThrownBy(() -> service.login(new LoginRequest("alice", "secret123")))
                .isInstanceOf(ForbiddenException.class)
                .hasMessage("没有执行该操作的权限");

        verify(tokenGenerator, never()).generate();
        verify(authTokenStore, never()).saveToken(anyString(), any(), anyString(), any());
    }

    @Test
    void rejectsInvalidRequestBeforeDatabaseLookup() {
        assertThatThrownBy(() -> service.login(new LoginRequest(" ", " ")))
                .isInstanceOf(InvalidRequestException.class);

        verify(userMapper, never()).selectOne(any());
    }

    @Test
    void logoutDeletesOnlyCurrentToken() {
        setAuthenticatedContext("10001", List.of("USER"));
        UsernamePasswordAuthenticationToken authentication =
                (UsernamePasswordAuthenticationToken) SecurityContextHolder.getContext().getAuthentication();
        authentication.setDetails("current-token");

        service.logout();

        verify(authTokenStore).deleteToken("current-token");
        verify(authTokenStore, never()).deleteAllTokensForUser(anyString());
    }

    @Test
    void logoutRejectsMissingBlankOrInvalidTokenDetails() {
        for (Object details : new Object[]{null, "   ", 123L}) {
            setAuthenticatedContext("10001", List.of("USER"));
            UsernamePasswordAuthenticationToken authentication =
                    (UsernamePasswordAuthenticationToken) SecurityContextHolder.getContext().getAuthentication();
            authentication.setDetails(details);

            assertThatThrownBy(() -> service.logout())
                    .isInstanceOf(AuthenticationFailureException.class);
        }

        org.mockito.Mockito.verifyNoInteractions(authTokenStore);
    }

    @Test
    void meReturnsSessionWithUserAndRoles() {
        User user = normalUser();
        setAuthenticatedContext("10001", List.of("USER", "ADMIN"));
        when(userMapper.selectById(10001L)).thenReturn(user);

        SessionVO result = service.me();

        assertThat(result.user().id()).isEqualTo("10001");
        assertThat(result.user().username()).isEqualTo("alice");
        assertThat(result.roles()).containsExactly("USER", "ADMIN");
    }

    @Test
    void meThrowsWhenUserNotFound() {
        setAuthenticatedContext("10001", List.of("USER"));
        when(userMapper.selectById(10001L)).thenReturn(null);

        assertThatThrownBy(() -> service.me())
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void logoutThrowsWhenNotAuthenticated() {
        assertThatThrownBy(() -> service.logout())
                .isInstanceOf(AuthenticationFailureException.class)
                .hasMessage("用户名或密码错误");
    }

    private static void setAuthenticatedContext(String userId, List<String> roles) {
        List<SimpleGrantedAuthority> authorities = roles.stream()
                .map(r -> "ROLE_" + r)
                .map(SimpleGrantedAuthority::new)
                .toList();
        Authentication authentication = new UsernamePasswordAuthenticationToken(
                userId, null, authorities);
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }

    private static User normalUser() {
        User user = User.register("alice", "$2a$10$encoded", "Alice", "avatar");
        user.setId(10001L);
        return user;
    }
}
