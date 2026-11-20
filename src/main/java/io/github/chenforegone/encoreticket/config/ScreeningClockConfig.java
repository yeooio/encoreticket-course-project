package io.github.chenforegone.encoreticket.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 场次及占座业务共享可替换的 UTC 时钟。
 * @author c1342
 */
@Configuration
public class ScreeningClockConfig {
    @Bean
    Clock screeningClock() {
        return Clock.systemUTC();
    }
}
