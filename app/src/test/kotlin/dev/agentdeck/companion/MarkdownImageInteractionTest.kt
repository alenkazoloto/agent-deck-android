package dev.agentdeck.companion

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import dev.agentdeck.companion.ui.AgentDeckTheme
import dev.agentdeck.companion.ui.MarkdownText
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** An image in an agent reply must leave something on screen: the app has no image loader. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
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
}
