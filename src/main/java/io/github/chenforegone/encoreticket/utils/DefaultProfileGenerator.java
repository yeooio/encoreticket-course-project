package io.github.chenforegone.encoreticket.utils;

import org.springframework.stereotype.Component;

import java.util.concurrent.ThreadLocalRandom;

/**
 * 用户默认资料生成器。
 *
 * <p>注册时为新用户生成随机昵称与默认头像。生成逻辑放在实体之外，实体因此保持纯装配、
 * 不依赖随机源，Service 也可以在测试中替换这一协作者。
 *
 * @author c1342
 */
@Component
public class DefaultProfileGenerator {

    /** 默认昵称前缀。 */
    private static final String NICKNAME_PREFIX = "用户";

    /** 默认昵称数字后缀下界，含。 */
    private static final int NICKNAME_SUFFIX_ORIGIN = 100_000;

    /** 默认昵称数字后缀上界，不含。 */
    private static final int NICKNAME_SUFFIX_BOUND = 1_000_000;

    /** 由应用自身提供的唯一默认头像，不依赖外部存储。 */
    public static final String DEFAULT_AVATAR = "/images/default-avatar.svg";

    /**
     * 生成默认昵称，格式为「用户 + 六位数字」。
     *
     * @return 默认昵称
     */
    public String generateNickname() {
        int suffix = ThreadLocalRandom.current()
                .nextInt(NICKNAME_SUFFIX_ORIGIN, NICKNAME_SUFFIX_BOUND);
        return NICKNAME_PREFIX + suffix;
    }

    /**
     * 返回统一的项目内置默认头像。
     *
     * @return 默认头像地址
     */
    public String generateAvatar() {
        return DEFAULT_AVATAR;
    }
}
