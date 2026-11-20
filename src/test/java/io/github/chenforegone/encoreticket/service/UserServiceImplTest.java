package io.github.chenforegone.encoreticket.service;

import io.github.chenforegone.encoreticket.converter.UserConverter;
import io.github.chenforegone.encoreticket.common.exception.BusinessRuleException;
import io.github.chenforegone.encoreticket.mapper.UserMapper;
import io.github.chenforegone.encoreticket.mapper.UserRoleMapper;
import io.github.chenforegone.encoreticket.model.dto.RegisterRequest;
import io.github.chenforegone.encoreticket.model.dto.ChangePasswordRequest;
import io.github.chenforegone.encoreticket.model.dto.UpdateProfileRequest;
import io.github.chenforegone.encoreticket.model.entity.User;
import io.github.chenforegone.encoreticket.model.entity.UserRole;
import io.github.chenforegone.encoreticket.model.vo.LoginVO;
import io.github.chenforegone.encoreticket.model.vo.NicknameSuggestionVO;
import io.github.chenforegone.encoreticket.security.token.AuthTokenStore;
import io.github.chenforegone.encoreticket.security.token.TokenGenerator;
import io.github.chenforegone.encoreticket.service.impl.UserServiceImpl;
import io.github.chenforegone.encoreticket.utils.DefaultProfileGenerator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class UserServiceImplTest {

    private UserMapper userMapper;
    private UserRoleMapper userRoleMapper;
    private PasswordEncoder passwordEncoder;
    private TokenGenerator tokenGenerator;
    private AuthTokenStore authTokenStore;
    private DefaultProfileGenerator profiles;
    private UserService service;

    @BeforeEach
    void setUp() {
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(
                new org.apache.ibatis.builder.MapperBuilderAssistant(
                        new org.apache.ibatis.session.Configuration(), ""), User.class);
        userMapper = mock(UserMapper.class);
        userRoleMapper = mock(UserRoleMapper.class);
        passwordEncoder = mock(PasswordEncoder.class);
        tokenGenerator = mock(TokenGenerator.class);
        authTokenStore = mock(AuthTokenStore.class);
        profiles = mock(DefaultProfileGenerator.class);
        when(profiles.generateNickname()).thenReturn("用户123456");
        when(profiles.generateAvatar()).thenReturn("https://cdn.example.com/avatar/default-1.png");
        when(passwordEncoder.encode("secret123")).thenReturn("$2a$10$encoded");
        when(tokenGenerator.generate()).thenReturn("opaque-token");
        when(userMapper.existsByUsername(anyString())).thenReturn(false);
        when(userMapper.existsByEmail(anyString())).thenReturn(false);
        when(userMapper.existsByPhone(anyString())).thenReturn(false);
        doAnswer(invocation -> {
            User user = invocation.getArgument(0);
            user.setId(10001L);
            return 1;
        }).when(userMapper).insert(any(User.class));

        service = new UserServiceImpl(
                userMapper,
                userRoleMapper,
                new UserConverter(),
                profiles,
                passwordEncoder,
                tokenGenerator,
                authTokenStore
        );
    }

    @Test
    void registersLowercaseUserWithDefaultRoleAndLoginToken() {
        LoginVO result = service.register(
                new RegisterRequest("Alice", "secret123", "secret123", "Alice", null, "", "")
        );

        assertThat(result.accessToken()).isEqualTo("opaque-token");
        assertThat(result.user().id()).isEqualTo("10001");
        assertThat(result.user().username()).isEqualTo("alice");
        assertThat(result.user().nickname()).isEqualTo("Alice");
        verify(profiles, never()).generateNickname();
        verify(userMapper).insert(argThat((User user) -> "Alice".equals(user.getNickname())));
        assertThat(result.roles()).containsExactly("USER");
        verify(authTokenStore).saveToken("10001", java.util.List.of("USER"), "opaque-token", 0L);
        verify(userRoleMapper).insert(argThat((UserRole role) -> role.getUserId().equals(10001L)
                && role.getRoleId().equals(3L)));
    }

    @Test
    void rejectsDuplicateUsernameBeforeWritingAnything() {
        when(userMapper.existsByUsername(anyString())).thenReturn(true);

        assertThatThrownBy(() -> service.register(
                new RegisterRequest("Alice", "secret123", "secret123", "Alice", null, null, null)
        )).hasMessage("资源已存在或状态发生冲突");

        verify(userMapper, never()).insert(any(User.class));
        verifyNoInteractions(userRoleMapper, authTokenStore, tokenGenerator, passwordEncoder);
    }

    @Test
    void rejectsWrongOldPasswordAsBusinessRuleViolation() {
        User user = User.register("alice", "$2a$10$encoded", "Alice", "avatar");
        user.setId(10001L);
        when(userMapper.selectById(10001L)).thenReturn(user);
        when(passwordEncoder.matches("wrong123", user.getPassword())).thenReturn(false);

        assertThatThrownBy(() -> service.changePassword(
                "10001", new ChangePasswordRequest("wrong123", "new12345", "new12345")))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessage("当前操作不符合业务规则");

        verify(userMapper, never()).updatePasswordByCas(anyLong(), anyLong(), anyString());
        verify(userMapper, never()).updateById(any(User.class));
        verify(userMapper, never()).update(any(User.class), any());
        verify(passwordEncoder, never()).encode(anyString());
        verifyNoInteractions(authTokenStore);
    }

    @Test
    void rejectsPasswordEqualToUsernameIgnoringCaseWithoutWriting() {
        User user = User.register("alice123", "old-hash", "Alice", "avatar");
        user.setId(10001L);
        when(userMapper.selectById(10001L)).thenReturn(user);
        when(passwordEncoder.matches("secret123", "old-hash")).thenReturn(true);

        assertThatThrownBy(() -> service.changePassword("10001",
                new ChangePasswordRequest("secret123", "ALICE123", "ALICE123")))
                .isInstanceOf(io.github.chenforegone.encoreticket.common.exception.InvalidRequestException.class)
                .satisfies(error -> assertThat(((io.github.chenforegone.encoreticket.common.exception.ApplicationException) error)
                        .getErrorCode()).isEqualTo(io.github.chenforegone.encoreticket.common.error.ErrorCode.INVALID_ARGUMENT));

        verify(userMapper).selectById(10001L);
        verifyNoMoreInteractions(userMapper);
        verify(passwordEncoder, never()).encode(anyString());
        verifyNoInteractions(authTokenStore);
    }

    @Test
    void missingUserCannotChangePasswordOrRevokeSessions() {
        assertThatThrownBy(() -> service.changePassword("10001",
                new ChangePasswordRequest("secret123", "new12345", "new12345")))
                .isInstanceOf(io.github.chenforegone.encoreticket.common.exception.ResourceNotFoundException.class);

        verify(userMapper).selectById(10001L);
        verifyNoMoreInteractions(userMapper);
        verifyNoInteractions(passwordEncoder, authTokenStore);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints = {0, 2})
    void unsuccessfulCasReportsConflictWithoutRetryOrRevocation(int affectedRows) {
        User user = User.register("alice", "old-hash", "Alice", "avatar");
        user.setId(10001L);
        user.setAuthVersion(7L);
        when(userMapper.selectById(10001L)).thenReturn(user);
        when(passwordEncoder.matches("secret123", "old-hash")).thenReturn(true);
        when(passwordEncoder.encode("new12345")).thenReturn("new-hash");
        when(userMapper.updatePasswordByCas(10001L, 7L, "new-hash")).thenReturn(affectedRows);

        assertThatThrownBy(() -> service.changePassword("10001",
                new ChangePasswordRequest("secret123", "new12345", "new12345")))
                .isInstanceOf(io.github.chenforegone.encoreticket.common.exception.ApplicationException.class)
                .satisfies(error -> {
                    var application = (io.github.chenforegone.encoreticket.common.exception.ApplicationException) error;
                    assertThat(application.getErrorCode()).isEqualTo(
                            io.github.chenforegone.encoreticket.common.error.ErrorCode.RESOURCE_CONFLICT);
                    assertThat(application.getHttpStatus()).isEqualTo(org.springframework.http.HttpStatus.CONFLICT);
                });

        verify(userMapper).selectById(10001L);
        verify(userMapper).updatePasswordByCas(10001L, 7L, "new-hash");
        verifyNoMoreInteractions(userMapper);
        verify(passwordEncoder).encode("new12345");
        verifyNoInteractions(authTokenStore);
    }

    @Test
    void avatarOnlyUpdatePreservesConfirmedNicknameAndContacts() {
        User user = User.register("alice", "$2a$10$encoded", "已确认昵称", "old-avatar");
        user.setId(10001L);
        user.setEmail("alice@example.com");
        user.setPhone("13800000000");
        when(userMapper.selectById("10001")).thenReturn(user);

        var result = service.updateProfile("10001",
                new UpdateProfileRequest(null, "https://cdn.example.com/new.png", null, null));

        assertThat(result.nickname()).isEqualTo("已确认昵称");
        assertThat(result.avatar()).isEqualTo("https://cdn.example.com/new.png");
        assertThat(result.email()).isEqualTo("alice@example.com");
        assertThat(result.phone()).isEqualTo("13800000000");
        verify(userMapper).updateById(argThat((User patch) -> patch.getPassword() == null
                && "https://cdn.example.com/new.png".equals(patch.getAvatar()) && patch.getNickname() == null));
        verifyNoInteractions(profiles, authTokenStore);
    }

    @Test
    void passwordChangeRevokesAllUserTokens() {
        User user = User.register("alice", "$2a$10$encoded", "Alice", "avatar");
        user.setId(10001L);
        user.setAuthVersion(7L);
        when(userMapper.selectById(10001L)).thenReturn(user);
        when(userMapper.updatePasswordByCas(10001L, 7L, "encoded-new-password")).thenReturn(1);
        when(passwordEncoder.matches("secret123", user.getPassword())).thenReturn(true);
        when(passwordEncoder.encode("new12345")).thenReturn("encoded-new-password");

        service.changePassword("10001", new ChangePasswordRequest("secret123", "new12345", "new12345"));

        var order = inOrder(userMapper, authTokenStore);
        order.verify(userMapper).updatePasswordByCas(10001L, 7L, "encoded-new-password");
        order.verify(authTokenStore).deleteAllTokensForUser("10001");
        verify(authTokenStore, never()).deleteToken(anyString());
    }

    @Test
    void registrationWriteFailurePropagatesAndRetainsRollbackCompensation() {
        var failure = new org.springframework.data.redis.RedisConnectionFailureException("private-token");
        doThrow(failure).when(authTokenStore).saveToken(anyString(), any(), anyString(), anyLong());
        org.springframework.transaction.support.TransactionSynchronizationManager.initSynchronization();
        try {
            assertThatThrownBy(() -> service.register(new RegisterRequest(
                    "Alice", "secret123", "secret123", "Alice", null, null, null))).isSameAs(failure);
            for (var synchronization : org.springframework.transaction.support.TransactionSynchronizationManager.getSynchronizations()) {
                synchronization.afterCompletion(org.springframework.transaction.support.TransactionSynchronization.STATUS_ROLLED_BACK);
            }
            verify(authTokenStore).deleteToken("opaque-token");
        } finally {
            org.springframework.transaction.support.TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void committedRegistrationKeepsSessionButFailedCommitRemovesIt() {
        for (int completion : new int[]{org.springframework.transaction.support.TransactionSynchronization.STATUS_COMMITTED,
                org.springframework.transaction.support.TransactionSynchronization.STATUS_UNKNOWN}) {
            reset(authTokenStore);
            org.springframework.transaction.support.TransactionSynchronizationManager.initSynchronization();
            try {
                service.register(new RegisterRequest("Alice", "secret123", "secret123", "Alice", null, null, null));
                for (var synchronization : org.springframework.transaction.support.TransactionSynchronizationManager.getSynchronizations()) {
                    synchronization.afterCompletion(completion);
                }
                verify(authTokenStore, times(completion == 0 ? 0 : 1)).deleteToken("opaque-token");
            } finally {
                org.springframework.transaction.support.TransactionSynchronizationManager.clearSynchronization();
            }
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void redisRevocationFailureAbortsPasswordChange(boolean timeout) {
        User user = User.register("alice", "old-hash", "Alice", "avatar");
        user.setId(10001L);
        when(userMapper.selectById(10001L)).thenReturn(user);
        when(userMapper.updatePasswordByCas(10001L, 0L, "new-hash")).thenReturn(1);
        when(passwordEncoder.matches("secret123", "old-hash")).thenReturn(true);
        when(passwordEncoder.encode("new12345")).thenReturn("new-hash");
        RuntimeException failure = timeout ? new org.springframework.dao.QueryTimeoutException("模拟超时")
                : new org.springframework.data.redis.RedisConnectionFailureException("模拟连接故障");
        doThrow(failure).when(authTokenStore).deleteAllTokensForUser("10001");

        var assertion = assertThatThrownBy(() -> service.changePassword("10001",
                new ChangePasswordRequest("secret123", "new12345", "new12345")));
        if (timeout) {
            assertion.isInstanceOf(io.github.chenforegone.encoreticket.common.exception.DependencyUnavailableException.class)
                    .hasCause(failure);
        } else {
            assertion.isSameAs(failure);
        }
        var order = inOrder(userMapper, authTokenStore);
        order.verify(userMapper).updatePasswordByCas(10001L, 0L, "new-hash");
        order.verify(authTokenStore).deleteAllTokensForUser("10001");
        verify(authTokenStore, never()).saveToken(anyString(), anyList(), anyString(), anyLong());
    }

    @Test
    void profileUpdateCannotOverwritePasswordOrDirectlyChangeContacts() {
        User user = User.register("alice", "old-hash", "Alice", "avatar");
        user.setId(10001L);
        user.setEmail("confirmed@example.com");
        when(userMapper.selectById("10001")).thenReturn(user);
        service.updateProfile("10001", new UpdateProfileRequest("New", null, "unverified@example.com", "13800000000"));
        verify(userMapper).updateById(argThat((User patch) -> patch.getPassword() == null
                && patch.getAuthVersion() == null && patch.getEmail() == null && patch.getPhone() == null
                && "New".equals(patch.getNickname())));
    }

    @Test
    void passwordRequestNeverExposesSecretsInDiagnosticText() {
        assertThat(new ChangePasswordRequest("old-private", "new-private", "confirm-private").toString())
                .doesNotContain("old-private", "new-private", "confirm-private");
    }

    @Test
    void generatesNicknameSuggestionWithoutWritingUserData() {
        NicknameSuggestionVO result = service.generateNicknameSuggestion();

        assertThat(result.nickname()).isEqualTo("用户123456");
        verify(profiles).generateNickname();
        verifyNoInteractions(userMapper, userRoleMapper, authTokenStore, tokenGenerator, passwordEncoder);
    }
}
