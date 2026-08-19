package app.mnema.ai;

import app.mnema.ai.service.AiJobWorker;
import app.mnema.ai.support.PostgresIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest
@ActiveProfiles("test")
class AiApplicationTests extends PostgresIntegrationTest {

	@MockitoBean
	private AiJobWorker aiJobWorker;

	@Test
	void contextLoads() {
	}

}
