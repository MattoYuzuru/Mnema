package app.mnema.media.config;

import app.mnema.media.security.MediaInternalAuthProps;
import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MediaInternalAuthPropsTest {

    private final jakarta.validation.Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void rejectsAMissingInternalServiceToken() {
        assertThat(validator.validate(new MediaInternalAuthProps(" ")))
                .extracting(violation -> violation.getPropertyPath().toString())
                .containsExactly("internalToken");
    }
}
