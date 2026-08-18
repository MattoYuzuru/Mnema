package app.mnema.core;

import app.mnema.core.support.PostgresIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.info.InfoEndpoint;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = "MNEMA_BUILD_ID=test-release")
@ActiveProfiles("test")
class CoreApplicationTests extends PostgresIntegrationTest {

	@Autowired
	private InfoEndpoint infoEndpoint;

	@Test
	void contextLoads() {
	}

	@Test
	void exposesTheDeployedBuildIdentity() {
		assertThat(infoEndpoint.info()).containsEntry("build", Map.of("id", "test-release"));
	}

}
