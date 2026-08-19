package app.mnema.ai.client.core;

import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CoreClientPropsValidationTest {

    private final jakarta.validation.Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void rejectsAMissingInternalServiceToken() {
        assertThat(validator.validate(new CoreClientProps("http://core.test", " ")))
                .extracting(violation -> violation.getPropertyPath().toString())
                .containsExactly("internalToken");
    }
}
