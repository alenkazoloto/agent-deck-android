package dev.agentdeck.companion

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import dev.agentdeck.companion.data.ComposerStashes
import dev.agentdeck.companion.data.PairedMachine
import dev.agentdeck.companion.data.SecureStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * P15: the desk's composer stash on the phone, through the real view model and the real
 * `SecureStore` — a stash that only lived in memory would pass every assertion but the restart one.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ComposerStashTest {

    private lateinit var app: Application
    private lateinit var store: SecureStore

    @Before fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        app = ApplicationProvider.getApplicationContext()
        store = SecureStore(app)
        store.save(MACHINE)
    }

    @After fun tearDown() {
        store.forget(MACHINE.id)
        Dispatchers.resetMain()
    }

    @Test fun `stashing empties the composer and the message survives a restart`() {
        val model = DeckViewModel(app)
        model.setDraft(KEY, "rename the parser after the tests pass")

        model.stashDraft(KEY)

        assertEquals("", model.draft(KEY))
        val reopened = DeckViewModel(app)
        assertEquals(
            listOf("rename the parser after the tests pass"),
            ComposerStashes.entries(reopened.state.value.drafts, KEY).map { it.text },
        )
    }

    @Test fun `restoring puts the message back and parks whatever was typed instead`() {
        val model = DeckViewModel(app)
        model.setDraft(KEY, "first")
        model.stashDraft(KEY)
        model.setDraft(KEY, "second")
        val first = ComposerStashes.entries(model.state.value.drafts, KEY).single()

        model.restoreStashed(KEY, first.id)

        assertEquals("first", model.draft(KEY))
        assertEquals(listOf("second"), ComposerStashes.entries(model.state.value.drafts, KEY).map { it.text })
    }

    @Test fun `discarding drops one entry and leaves the draft and other chats alone`() {
        val model = DeckViewModel(app)
        model.setDraft(KEY, "keep me")
        model.stashDraft(KEY)
        model.setDraft(KEY, "drop me")
        model.stashDraft(KEY)
        model.setDraft(OTHER, "another chat")
        model.stashDraft(OTHER)
        model.setDraft(KEY, "typing")
        val drop = ComposerStashes.entries(model.state.value.drafts, KEY).first { it.text == "drop me" }

        model.discardStashed(KEY, drop.id)

        assertEquals("typing", model.draft(KEY))
        assertEquals(listOf("keep me"), ComposerStashes.entries(model.state.value.drafts, KEY).map { it.text })
        assertEquals(listOf("another chat"), ComposerStashes.entries(model.state.value.drafts, OTHER).map { it.text })
    }

    @Test fun `a blank composer parks nothing`() {
        val model = DeckViewModel(app)
        model.setDraft(KEY, "   ")

        model.stashDraft(KEY)

        assertEquals("   ", model.draft(KEY))
        assertTrue(ComposerStashes.entries(model.state.value.drafts, KEY).isEmpty())
    }

    private companion object {
        const val KEY = "claude:/repo:session"
        const val OTHER = "claude:/repo:other"
        val MACHINE = PairedMachine(
            machineName = "desk", hosts = listOf("machine.test"), port = 8443,
            spkiFingerprint = "00", token = "token", deviceId = "device-1",
        )
    }
}
