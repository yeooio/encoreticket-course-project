package io.github.chenforegone.encoreticket.service;

import io.github.chenforegone.encoreticket.gateway.RefundGateway;
import io.github.chenforegone.encoreticket.service.impl.RefundServiceImpl;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * 退款处理服务边界；数据库和时间为外部边界。
 * @author c1342
 */
class RefundServiceTest {
    @Test
    void applicationRejectsInvalidKeyWithoutAccessingDatabase() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        var service = new RefundServiceImpl(new io.github.chenforegone.encoreticket.mapper.RefundProcessingMapper(jdbc), mock(PlatformTransactionManager.class),
                () -> java.time.Instant.parse("2026-10-20T05:00:00Z"), JsonMapper.builder().build(), mock(RefundGateway.class));
        assertThatThrownBy(() -> service.apply(10L, 20L, "bad key"))
                .isInstanceOf(io.github.chenforegone.encoreticket.common.exception.InvalidRequestException.class);
        verifyNoInteractions(jdbc);
    }
}
