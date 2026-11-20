package io.github.chenforegone.encoreticket;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

@SpringBootTest
@TestPropertySource(properties = {
        "spring.flyway.enabled=false",
        "encore.refund.scheduler-enabled=false",
        "encore.movie.publication-scheduler.enabled=false",
        "encore.screening.lifecycle-scheduler.enabled=false",
        "encore.order.expiration-scheduler.enabled=false"
})
class EncoreTicketBackendApplicationTests {

    @Test
    void contextLoads() {
    }

}
