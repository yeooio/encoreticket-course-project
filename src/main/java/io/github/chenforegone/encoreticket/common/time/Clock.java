package io.github.chenforegone.encoreticket.common.time;

import java.time.Instant;

/**
 * 为业务服务提供可替换的当前时间。
 *
 * @author c1342
 */
public interface Clock {
    Instant instant();
}
