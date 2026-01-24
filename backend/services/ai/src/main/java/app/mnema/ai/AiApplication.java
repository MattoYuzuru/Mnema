package app.mnema.ai;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@ConfigurationPropertiesScan
@SpringBootApplication
public class AiApplication {

	public static void main(String[] args) {
		SpringApplication.run(AiApplication.class, args);
	}

}
