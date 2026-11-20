package io.github.chenforegone.encoreticket.service;

import io.github.chenforegone.encoreticket.converter.UserConverter;
import io.github.chenforegone.encoreticket.mapper.UserMapper;
import io.github.chenforegone.encoreticket.mapper.UserRoleMapper;
import io.github.chenforegone.encoreticket.model.dto.RegisterRequest;
import io.github.chenforegone.encoreticket.model.entity.User;
import io.github.chenforegone.encoreticket.security.token.AuthTokenStore;
import io.github.chenforegone.encoreticket.security.token.TokenGenerator;
import io.github.chenforegone.encoreticket.service.impl.UserServiceImpl;
import io.github.chenforegone.encoreticket.utils.DefaultProfileGenerator;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 默认头像在注册和本人资料服务边界的行为。
 *
 * @author c1342
 */
class DefaultAvatarServiceTest {
    @Test
    void registrationUsesPackagedAvatarWhileExistingCustomAvatarRemainsUnchanged() {
        var users = mock(UserMapper.class);
        var passwords = mock(PasswordEncoder.class);
        var tokens = mock(TokenGenerator.class);
        when(passwords.encode("secret123")).thenReturn("hash");
        when(tokens.generate()).thenReturn("token");
        doAnswer(call -> {
            ((User) call.getArgument(0)).setId(1001L);
            return 1;
        }).when(users).insert(any(User.class));
        var service = new UserServiceImpl(users, mock(UserRoleMapper.class), new UserConverter(),
                new DefaultProfileGenerator(), passwords, tokens, mock(AuthTokenStore.class));
        var registered = service.register(new RegisterRequest("alice", "secret123", "secret123", "Alice", null, null, null));
        assertThat(registered.user().avatar()).isEqualTo("/images/default-avatar.svg");
        var existing = User.register("alice", "hash", "Alice", "/custom/alice.png");
        existing.setId(1001L);
        when(users.selectById("1001")).thenReturn(existing);
        assertThat(service.getCurrentUser("1001").avatar()).isEqualTo("/custom/alice.png");
        assertThat(existing.getAvatar()).isEqualTo("/custom/alice.png");
        verify(users, never()).updateById(any(User.class));
    }
}
