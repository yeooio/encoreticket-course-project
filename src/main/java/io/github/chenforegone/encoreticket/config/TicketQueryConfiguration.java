package io.github.chenforegone.encoreticket.config;

import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 可在测试中替换的票券查询时钟。
 *
 * @author c1342
 */
@Configuration(proxyBeanMethods = false)
public class TicketQueryConfiguration {
    @Bean
    @ConditionalOnMissingBean(Clock.class)
    public Clock ticketQueryClock() {
        return Clock.systemUTC();
    }
}
