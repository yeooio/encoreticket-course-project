package io.github.chenforegone.encoreticket.scheduler;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 定时任务开关允许查询或测试进程显式关闭后台写入。
 *
 * @author c1342
 */
class ScreeningSchedulersTest {

    @Test
    void disablingSchedulersAvoidsCreatingBackgroundWriters() {
        new ApplicationContextRunner()
                .withUserConfiguration(ScreeningLifecycleScheduler.class, OrderExpirationScheduler.class)
                .withPropertyValues("encore.screening.lifecycle-scheduler.enabled=false",
                        "encore.order.expiration-scheduler.enabled=false")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(ScreeningLifecycleScheduler.class);
                    assertThat(context).doesNotHaveBean(OrderExpirationScheduler.class);
                });
    }
}
