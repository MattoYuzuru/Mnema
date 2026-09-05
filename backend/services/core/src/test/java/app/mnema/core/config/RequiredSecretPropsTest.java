package app.mnema.core.config;

import app.mnema.core.media.config.MediaClientProps;
import app.mnema.core.security.CoreInternalAuthProps;
import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RequiredSecretPropsTest {

    private final jakarta.validation.Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void rejectsMissingInternalServiceTokens() {
        assertThat(validator.validate(new MediaClientProps("http://media.test", " ")))
                .extracting(violation -> violation.getPropertyPath().toString())
                .containsExactly("internalToken");
        assertThat(validator.validate(new CoreInternalAuthProps("")))
                .extracting(violation -> violation.getPropertyPath().toString())
                .containsExactly("internalToken");
    }
}
