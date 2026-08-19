package app.mnema.user.config

import app.mnema.user.media.config.MediaClientProps
import app.mnema.user.security.UserInternalAuthProps
import jakarta.validation.Validation
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class RequiredSecretPropsTest {
    private val validator = Validation.buildDefaultValidatorFactory().validator

    @Test
    fun `rejects missing media and user service tokens`() {
        val mediaViolations = validator.validate(MediaClientProps("http://media.test", ""))
        val userViolations = validator.validate(UserInternalAuthProps(" "))

        assertEquals(setOf("internalToken"), mediaViolations.map { it.propertyPath.toString() }.toSet())
        assertEquals(setOf("internalToken"), userViolations.map { it.propertyPath.toString() }.toSet())
    }
}
