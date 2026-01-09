package app.mnema.importer;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class ImportApplication {

    public static void main(String[] args) {
        SpringApplication.run(ImportApplication.class, args);
    }
}
