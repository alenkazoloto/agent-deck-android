package dev.agentdeck.companion

import dev.agentdeck.companion.data.AppLog
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppLogTest {

    @Test fun `a bearer token and a keyed secret never leave the phone`() {
        val out = AppLog.prepare(
            "D/Bridge: Authorization: Bearer abcDEF123456789xyz\nI/Push: authSecret=Zm9vYmFyYmF6 endpoint ok\nplain line",
            "header",
        )
        assertFalse(out, out.contains("abcDEF123456789xyz"))
        assertFalse(out, out.contains("Zm9vYmFyYmF6"))
        assertTrue(out.contains("plain line"))
        assertTrue(out.startsWith("header"))
    }

    @Test fun `an empty log still says so`() {
        assertTrue(AppLog.prepare("", "h").contains("(empty)"))
    }
}
