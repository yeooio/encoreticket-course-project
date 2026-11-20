package io.github.chenforegone.encoreticket.simulation.payment;


import io.github.chenforegone.encoreticket.service.PaymentChannelResultHandler;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.simple.JdbcClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * 测试入口和服务在生产混合环境中必须失败关闭。
 * @author c1342
 */
class PaymentSimulationIsolationTest {
    @Test
    void registrationRequiresExplicitSwitchAndAllowedProfileWithoutProd() {
        for (String profiles : java.util.List.of("", "local", "test", "loadtest", "prod", "staging",
                "local,prod", "test,prod", "loadtest,prod", "local,test,loadtest,prod", "local,test,loadtest")) {
            for (String enabled : java.util.List.of("missing", "false", "true")) {
                var runner = new ApplicationContextRunner()
                        .withUserConfiguration(AdminPaymentSimulationController.class, PaymentSimulationService.class)
                        .withBean(JdbcClient.class, () -> mock(JdbcClient.class))
                        .withBean(PaymentChannelResultHandler.class, () -> mock(PaymentChannelResultHandler.class))
                        .withPropertyValues("spring.profiles.active=" + profiles);
                if (!enabled.equals("missing")) runner = runner.withPropertyValues("encore.payment.simulation.enabled=" + enabled);
                boolean expected = enabled.equals("true") && !profiles.contains("prod")
                        && (profiles.contains("local") || profiles.contains("test"));
                runner.run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBeansOfType(AdminPaymentSimulationController.class)).hasSize(expected ? 1 : 0);
                    assertThat(context.getBeansOfType(PaymentSimulationService.class)).hasSize(expected ? 1 : 0);
                });
            }
        }
    }
}
