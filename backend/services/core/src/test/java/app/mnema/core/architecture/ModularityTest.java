package app.mnema.core.architecture;

import app.mnema.core.CoreApplication;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.modulith.core.ApplicationModules;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
class ModularityTest {

    @Autowired
    ApplicationContext context;

    @Test
    void verifyModules() {
        ApplicationModules modules = ApplicationModules.of(CoreApplication.class);
        modules.verify(); // упадёт, если есть запрещённые связи
    }
}
