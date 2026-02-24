package pl.magisterka.backend;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Backend API (REST, analiza, baza).
 * Wymaga bazy: domyślnie PostgreSQL (localhost:5432/smarthome).
 * Bez PostgreSQL: uruchom z profilem H2, np. -Dspring.profiles.active=h2
 * (wtedy baza w pliku ./data/smarthome).
 */
@SpringBootApplication
@EnableScheduling
public class BackendApplication {
    public static void main(String[] args) {
        SpringApplication.run(BackendApplication.class, args);
    }
}
