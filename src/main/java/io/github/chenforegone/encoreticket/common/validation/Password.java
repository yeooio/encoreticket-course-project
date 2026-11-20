package io.github.chenforegone.encoreticket.common.validation;

import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

/**
 * 密码强度校验注解。
 *
 * <p>校验规则：长度 8-64 位，必须同时包含字母和数字，且 UTF-8 编码后不超过 72 字节
 *（BCrypt 硬性限制）。集中定义避免在多个 DTO 中重复注解组合。</p>
 *
 * @author c1342
 */
@Documented
@Constraint(validatedBy = Password.PasswordValidator.class)
@Target({ElementType.FIELD, ElementType.PARAMETER})
@Retention(RetentionPolicy.RUNTIME)
public @interface Password {

    String message() default "密码长度必须为 8-64 位，且必须同时包含字母和数字";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};

    /**
     * 密码强度校验实现。
     *
     * @author c1342
     */
    class PasswordValidator implements ConstraintValidator<Password, String> {

        private static final Pattern LETTER_AND_DIGIT = Pattern.compile("^(?=.*[A-Za-z])(?=.*\\d).+$");

        @Override
        public boolean isValid(String value, ConstraintValidatorContext context) {
            if (value == null || value.isBlank()) {
                return false;
            }
            if (value.length() < 8 || value.length() > 64) {
                return false;
            }
            if (!LETTER_AND_DIGIT.matcher(value).matches()) {
                return false;
            }
            return value.getBytes(StandardCharsets.UTF_8).length <= 72;
        }
    }
}
