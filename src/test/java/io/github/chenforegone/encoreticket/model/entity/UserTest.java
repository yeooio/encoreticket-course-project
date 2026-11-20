package io.github.chenforegone.encoreticket.model.entity;

import io.github.chenforegone.encoreticket.model.enums.UserStatus;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link User} 的创建与敏感字段保护测试。
 */
class UserTest {

    private static final String PASSWORD_HASH = "$2a$10$abcdefghijklmnopqrstuv";
    private static final String AVATAR = "https://cdn.example.com/avatar/default.png";

    @Test
    void toStringDoesNotExposePassword() {
        User user = new User();
        user.setId(10001L);
        user.setUsername("alice");
        user.setPassword(PASSWORD_HASH);

        String text = user.toString();

        assertTrue(text.contains("alice"), "toString should still identify the user");
        assertFalse(text.contains(PASSWORD_HASH), "toString must not contain the password hash");
        assertFalse(text.contains("password"), "toString must not reference the password field");
    }

    @Test
    void registerCreatesActiveUserWithRequiredFields() {
        User user = User.register("alice", PASSWORD_HASH, "Alice", AVATAR);

        assertEquals("alice", user.getUsername());
        assertEquals(PASSWORD_HASH, user.getPassword());
        assertEquals("Alice", user.getNickname());
        assertEquals(AVATAR, user.getAvatar());
        assertEquals(UserStatus.NORMAL, user.getStatus());
    }

    @Test
    void registerLeavesGeneratedFieldsAndOptionalContactsToCaller() {
        User user = User.register("alice", PASSWORD_HASH, "Alice", AVATAR);

        assertNull(user.getId(), "id is assigned by MyBatis-Plus on insert");
        assertNull(user.getCreateTime(), "createTime is filled by MyBatis-Plus on insert");
        assertNull(user.getUpdateTime(), "updateTime is filled by MyBatis-Plus on insert");
        assertNull(user.getEmail(), "email is optional and starts as null");
        assertNull(user.getPhone(), "phone is optional and starts as null");
    }
}
