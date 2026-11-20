package io.github.chenforegone.encoreticket.config;

import io.github.chenforegone.encoreticket.common.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Instant;

/**
 * 系统时钟配置。
 *
 * @author c1342
 */
@Configuration
public class ClockConfig {

    @Bean
    Clock clock() {
        return Instant::now;
    }
}
