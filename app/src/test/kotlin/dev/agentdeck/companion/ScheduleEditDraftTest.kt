package dev.agentdeck.companion

import com.github.claudeagents.core.mobile.*
import dev.agentdeck.companion.ui.scheduleEditInitial
import org.junit.Assert.*
import org.junit.Test

/** Prefills and incomplete drafts retain the task state before the editor renders. */
class ScheduleEditDraftTest {
    private val detail = MobileScheduleEditDetail(
        "task", "Review the patch", "/work/project", "chat", 1_800_000_000_000, 900_000, null,
        "sonnet", "CLAUDE", "personal", null, "Europe/Amsterdam", true, true,
        listOf(MobileScheduleAccountOption("personal", "Personal"), MobileScheduleAccountOption("work", "Work")),
    )

    @Test fun `choosing a different time starts from the existing host cadence and time`() {
        val form = scheduleEditInitial(detail, "")
        assertEquals(15, form.delayAmount)
        assertEquals("minutes", form.delayUnit)
        assertEquals("09:00", form.timeOfDay)
        assertEquals("keep", form.whenChoice)
        assertTrue(form.repeat)
        assertEquals("sonnet", form.model)
    }

    @Test fun `saved dependency selection and context survive reopening`() {
        val selected = listOf(MobileScheduleDependencySelection("source", true))
        val form = scheduleEditInitial(detail.copy(selectedDependencies = selected), "")
        assertEquals("dependencies", form.whenChoice)
        assertEquals(selected, form.dependencies)
    }

    @Test fun `incomplete draft restores without dropping fields`() {
        val saved = MobileScheduleEditRequest("", delayAmount = 0, model = "my-model").toJson().toString()
        val restored = scheduleEditInitial(detail, saved)
        assertEquals("", restored.prompt)
        assertEquals(0, restored.delayAmount)
        assertEquals("my-model", restored.model)
    }
}
