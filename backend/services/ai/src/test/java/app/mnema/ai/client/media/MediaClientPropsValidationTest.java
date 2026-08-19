package app.mnema.ai.client.media;

import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MediaClientPropsValidationTest {

    private final jakarta.validation.Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void rejectsAMissingInternalServiceToken() {
        assertThat(validator.validate(new MediaClientProps("http://media.test", " ")))
                .extracting(violation -> violation.getPropertyPath().toString())
                .containsExactly("internalToken");
    }
}
