package app.mnema.core;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.modulith.core.ApplicationModules;

@SpringBootTest
class ModularityTest {

    @Autowired
    ApplicationContext context;

    @Test
    void verifyModules() {
        ApplicationModules modules = ApplicationModules.of(CoreApplication.class);
        modules.verify(); // упадёт, если есть запрещённые связи
    }
}
