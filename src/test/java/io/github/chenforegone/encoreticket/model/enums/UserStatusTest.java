package io.github.chenforegone.encoreticket.model.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link UserStatus} 的数据库值与接口值映射测试。
 */
class UserStatusTest {

    @Test
    void mapsDatabaseValuesToStatus() {
        assertEquals(UserStatus.NORMAL, UserStatus.fromDbValue(0));
        assertEquals(UserStatus.DISABLED, UserStatus.fromDbValue(1));
    }

    @Test
    void exposesValueAndCode() {
        assertEquals(Integer.valueOf(0), UserStatus.NORMAL.getDbValue());
        assertEquals("NORMAL", UserStatus.NORMAL.getApiCode());
        assertEquals(Integer.valueOf(1), UserStatus.DISABLED.getDbValue());
        assertEquals("DISABLED", UserStatus.DISABLED.getApiCode());
    }

    @Test
    void rejectsUndefinedDatabaseValue() {
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> UserStatus.fromDbValue(9));
        assertTrue(exception.getMessage().contains("9"));
    }

    @Test
    void rejectsNullDatabaseValue() {
        assertThrows(IllegalArgumentException.class, () -> UserStatus.fromDbValue(null));
    }

    @Test
    void declaresEnumValueAnnotationForPersistenceMapping() throws NoSuchFieldException {
        Field field = UserStatus.class.getDeclaredField("dbValue");
        assertNotNull(
                field.getAnnotation(EnumValue.class),
                "dbValue 必须保留 @EnumValue 以映射 TINYINT 列");
    }
}
