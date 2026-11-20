package io.github.chenforegone.encoreticket.converter;

import io.github.chenforegone.encoreticket.model.entity.User;
import io.github.chenforegone.encoreticket.model.enums.UserStatus;
import io.github.chenforegone.encoreticket.model.vo.UserVO;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * {@link UserConverter} 的模型转换测试。
 */
class UserConverterTest {

    private static final Instant CREATE_TIME = Instant.parse("2026-09-13T02:30:00Z");
    private static final Instant UPDATE_TIME = Instant.parse("2026-09-14T02:30:00Z");

    private final UserConverter converter = new UserConverter();

    @Test
    void convertsEntityToViewWithStringIdAndApiStatus() {
        UserVO vo = converter.toVO(normalUser());

        assertNotNull(vo);
        assertEquals("10001", vo.id());
        assertEquals("alice", vo.username());
        assertEquals("Alice", vo.nickname());
        assertEquals("https://cdn.example.com/avatar/alice.png", vo.avatar());
        assertEquals("alice@example.com", vo.email());
        assertEquals("13800000000", vo.phone());
        assertEquals("NORMAL", vo.status());
        assertEquals(CREATE_TIME, vo.createTime());
        assertEquals(UPDATE_TIME, vo.updateTime());
    }

    @Test
    void mapsDisabledStatusToApiCode() {
        User user = normalUser();
        user.setStatus(UserStatus.DISABLED);

        assertEquals("DISABLED", converter.toVO(user).status());
    }

    @Test
    void toleratesNullStatusAndNullableContactFields() {
        User user = normalUser();
        user.setStatus(null);
        user.setEmail(null);
        user.setPhone(null);

        UserVO vo = converter.toVO(user);

        assertNull(vo.status());
        assertNull(vo.email());
        assertNull(vo.phone());
    }

    @Test
    void returnsNullForNullEntity() {
        assertNull(converter.toVO(null));
    }

    private static User normalUser() {
        User user = new User();
        user.setId(10001L);
        user.setUsername("alice");
        user.setPassword("$2a$10$not-a-real-hash");
        user.setNickname("Alice");
        user.setAvatar("https://cdn.example.com/avatar/alice.png");
        user.setEmail("alice@example.com");
        user.setPhone("13800000000");
        user.setStatus(UserStatus.NORMAL);
        user.setCreateTime(CREATE_TIME);
        user.setUpdateTime(UPDATE_TIME);
        return user;
    }
}
