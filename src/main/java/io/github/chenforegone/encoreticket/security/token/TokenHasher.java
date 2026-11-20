package io.github.chenforegone.encoreticket.security.token;

import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * 计算登录令牌的 SHA-256 摘要。
 *
 * <p>令牌是高熵随机值，摘要用于 Redis 键寻址，不用于密码校验，因此不需要慢哈希算法。
 *
 * @author c1342
 */
@Component
public class TokenHasher {

    /**
     * @param token 客户端令牌
     * @return 小写十六进制 SHA-256 摘要
     */
    public String hash(String token) {
        if (token == null || token.isBlank()) {
            throw new IllegalArgumentException("token must not be blank");
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }
}
