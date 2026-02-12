package app.mnema.ai;

import app.mnema.ai.support.PostgresIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
class AiApplicationTests extends PostgresIntegrationTest {

	@Test
	void contextLoads() {
	}

}
