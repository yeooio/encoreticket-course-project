package io.github.chenforegone.encoreticket;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class EncoreTicketBackendApplication {

    public static void main(String[] args) {
        SpringApplication.run(EncoreTicketBackendApplication.class, args);
    }

}
