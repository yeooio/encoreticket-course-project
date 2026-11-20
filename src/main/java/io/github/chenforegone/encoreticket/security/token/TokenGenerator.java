package io.github.chenforegone.encoreticket.security.token;

import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.util.Base64;

/**
 * 生成不透明的高熵访问令牌。
 *
 * @author c1342
 */
@Component
public class TokenGenerator {

    private static final int TOKEN_BYTES = 32;

    private final SecureRandom secureRandom = new SecureRandom();

    /**
     * 生成 256 位随机的 Base64URL 令牌。
     *
     * @return 不带填充字符的访问令牌
     */
    public String generate() {
        byte[] bytes = new byte[TOKEN_BYTES];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** 兼容旧调用方的命名别名。 */
    public String generateToken() {
        return generate();
    }
}
