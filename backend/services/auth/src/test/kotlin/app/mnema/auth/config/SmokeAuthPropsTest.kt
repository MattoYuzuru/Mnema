package app.mnema.auth.config

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class SmokeAuthPropsTest {

    @Test
    fun `disabled configuration never allows bypass`() {
        assertFalse(SmokeAuthProps().allows("mnema-smoke", "any-key"))
    }

    @Test
    fun `matching login and strong key allow bypass`() {
        val key = "release-smoke-key-that-is-longer-than-32-characters"
        val props = SmokeAuthProps("mnema-smoke", key)

        assertTrue(props.allows(" MNEMA-SMOKE ", key))
        assertFalse(props.allows("other-user", key))
        assertFalse(props.allows("mnema-smoke", "wrong-key"))
    }

    @Test
    fun `partial or weak configuration is rejected`() {
        assertThrows<IllegalArgumentException> { SmokeAuthProps(login = "mnema-smoke") }
        assertThrows<IllegalArgumentException> {
            SmokeAuthProps(login = " mnema-smoke ", turnstileBypassKey = "x".repeat(32))
        }
        assertThrows<IllegalArgumentException> {
            SmokeAuthProps(login = "mnema-smoke", turnstileBypassKey = "short")
        }
    }
}
