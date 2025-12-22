package app.mnema.core;

import app.mnema.core.support.PostgresIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
class CoreApplicationTests extends PostgresIntegrationTest {

	@Test
	void contextLoads() {
	}

}
