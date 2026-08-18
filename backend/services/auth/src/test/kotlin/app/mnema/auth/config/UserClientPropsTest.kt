package app.mnema.auth.config

import jakarta.validation.Validation
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class UserClientPropsTest {
    private val validator = Validation.buildDefaultValidatorFactory().validator

    @Test
    fun `rejects a missing internal service token`() {
        val violations = validator.validate(UserClientProps(internalToken = " "))

        assertEquals(setOf("internalToken"), violations.map { it.propertyPath.toString() }.toSet())
    }
}
