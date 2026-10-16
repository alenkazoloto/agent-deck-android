package dev.agentdeck.companion

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import dev.agentdeck.companion.ui.AgentDeckTheme
import dev.agentdeck.companion.ui.LocalMarkdownImages
import dev.agentdeck.companion.ui.MarkdownImageLoader
import dev.agentdeck.companion.ui.MarkdownText
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * An image in an agent reply must leave something on screen: the picture where the machine serves
 * it, the one-line note where it will not (or is not asked, on a web address).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MarkdownImageInteractionTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `a host-path image shows its alt text and where to see it`() {
        compose.setContent {
            AgentDeckTheme { MarkdownText("Look:\n\n![Mobile PNG test](</Users/example/assets/test.png>)") }
        }

        compose.onNodeWithText("Mobile PNG test", substring = true).assertIsDisplayed()
        compose.onNodeWithText("open in the IDE", substring = true).assertIsDisplayed()
    }

    @Test
    fun `an image the machine serves is drawn, and the note is gone`() {
        val asked = mutableListOf<String>()
        val loader: MarkdownImageLoader = { destination ->
            asked += destination
            ImageBitmap(40, 20)
        }
        compose.setContent {
            AgentDeckTheme {
                CompositionLocalProvider(LocalMarkdownImages provides loader) {
                    MarkdownText("Look:\n\n![Build shot](docs/shot.png)")
                }
            }
        }
        compose.waitForIdle()

        compose.onNodeWithContentDescription("Build shot").assertIsDisplayed()
        compose.onNodeWithText("open in the IDE", substring = true).assertDoesNotExist()
        assertEquals(listOf("docs/shot.png"), asked)
    }

    @Test
    fun `an image the machine refuses falls back to the note`() {
        val loader: MarkdownImageLoader = { null }
        compose.setContent {
            AgentDeckTheme {
                CompositionLocalProvider(LocalMarkdownImages provides loader) {
                    MarkdownText("![Build shot](docs/shot.png)")
                }
            }
        }
        compose.waitForIdle()

        compose.onNodeWithText("Build shot", substring = true).assertIsDisplayed()
        compose.onNodeWithText("open in the IDE", substring = true).assertIsDisplayed()
    }

    @Test
    fun `a web image is never asked of the machine`() {
        val asked = mutableListOf<String>()
        val loader: MarkdownImageLoader = { asked += it; ImageBitmap(4, 4) }
        compose.setContent {
            AgentDeckTheme {
                CompositionLocalProvider(LocalMarkdownImages provides loader) {
                    MarkdownText("![Logo](https://example.com/logo.png)")
                }
            }
        }
        compose.waitForIdle()

        assertEquals(emptyList<String>(), asked)
        compose.onNodeWithText("open in the IDE", substring = true).assertIsDisplayed()
    }
}
