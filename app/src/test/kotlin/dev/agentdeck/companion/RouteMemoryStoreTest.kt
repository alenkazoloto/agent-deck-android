package dev.agentdeck.companion

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.github.claudeagents.core.reach.Route
import com.github.claudeagents.core.reach.RouteMemory
import dev.agentdeck.companion.data.PairedMachine
import dev.agentdeck.companion.data.SecureStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Route memory lives beside the pairings it describes, and leaves with them. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RouteMemoryStoreTest {

    private val lan = Route(Route.LAN, "192.168.1.24", 63350)
    private val relay = Route(Route.RELAY, "k3x.relay.example", 443)

    private fun machine(deviceId: String) = PairedMachine("desk", listOf(lan.host), 63350, "00", "t", deviceId)

    @Test fun `memory survives a restart of the store`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val memory = RouteMemory.EMPTY
            .recordWin("a", "home", lan)
            .recordWin("a", "cafe", relay, listOf(lan))

        SecureStore(app).saveRouteMemory(memory)

        assertEquals(memory, SecureStore(app).routeMemory())
    }

    @Test fun `unpairing forgets that machine's memory and keeps the others'`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val store = SecureStore(app)
        store.save(machine("a"))
        store.save(machine("b"))
        store.saveRouteMemory(RouteMemory.EMPTY.recordWin("a", "home", lan).recordWin("b", "home", relay))

        store.forget("a")

        val left = SecureStore(app).routeMemory()
        assertNull(left.network("a", "home"))
        assertNotNull(left.network("b", "home"))
    }

    @Test fun `unreadable memory is empty, never an error`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        assertEquals(RouteMemory.EMPTY, SecureStore(app).routeMemory())
    }
}
