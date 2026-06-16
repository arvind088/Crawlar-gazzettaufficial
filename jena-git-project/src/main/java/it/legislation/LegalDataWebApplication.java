package it.legislation;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class LegalDataWebApplication {

    public static void main(String[] args) {
        SpringApplication.run(LegalDataWebApplication.class, args);
    }
}
