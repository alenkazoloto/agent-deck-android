package dev.agentdeck.companion

import com.github.claudeagents.core.AgentVendor
import com.github.claudeagents.core.mobile.MobileProtocol
import dev.agentdeck.companion.data.AfterDelay
import dev.agentdeck.companion.data.AtTime
import dev.agentdeck.companion.data.NewChatTarget
import dev.agentdeck.companion.data.ScheduleHabit
import dev.agentdeck.companion.data.ScheduleHabit.Due
import dev.agentdeck.companion.data.ScheduleWhen
import dev.agentdeck.companion.ui.ScheduleDuePick
import dev.agentdeck.companion.ui.openingTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** mobile-todo Scheduling: the schedule dialogs open on the last scheduling, as the desk's `ScheduleDefaults` does. */
class ScheduleHabitTest {
    private val target = NewChatTarget(
        "/work/plugin", AgentVendor.CODEX, model = "gpt-5", accountId = "work", effort = "high",
        permissionMode = "workspace-write", fastMode = true, thinking = false,
    )

    @Test fun `the habit survives its own JSON, target and typed values included`() {
        val habit = ScheduleHabit(Due.AT, ScheduleWhen.THIS_EVENING, 30, true, "07:45", true, target)
        assertEquals(habit, ScheduleHabit.fromJson(MobileProtocol.parseObject(habit.toJson().toString())))
        assertEquals(ScheduleHabit(), ScheduleHabit.fromJson(MobileProtocol.parseObject(ScheduleHabit().toJson().toString())))
    }

    @Test fun `a stored habit that is garbage degrades to the defaults instead of failing the dialog`() {
        val hostile = MobileProtocol.parseObject(
            """{"due":"SOON","relative":7,"inAmount":100000,"atTime":"25:99","repeat":"yes",
               "target":{"project":"/p","vendor":"NOPE"}}""",
        )
        val habit = ScheduleHabit.fromJson(hostile)
        assertNull(habit.due)
        assertEquals(ScheduleWhen.IN_AN_HOUR, habit.relative)
        assertEquals(AfterDelay.MAX_AMOUNT, habit.inAmount)
        assertEquals("", habit.atTime)
        assertFalse(habit.repeat)
        assertNull(habit.target)
        assertEquals(ScheduleHabit(), ScheduleHabit.fromJson(null))
    }

    @Test fun `a typed In and At pick reopens with its own values and repeats to match`() {
        val pick = ScheduleDuePick().apply {
            custom = ScheduleDuePick.CustomDue.IN
            delayText = "45"
            delayMinutes = true
        }
        val habit = pick.remembered(ScheduleHabit(), repeat = true)
        assertEquals(Due.IN, habit.due)
        val reopened = ScheduleDuePick.opening(null, habit)
        assertEquals(AfterDelay(45, minutes = true), reopened.picked(null))
        assertEquals("Repeat every 45 minutes", reopened.picked(null).repeatLabel())
        assertTrue(habit.repeat)

        val at = ScheduleDuePick.opening(null, habit).apply { custom = ScheduleDuePick.CustomDue.AT; clockText = " 07:45 " }
            .remembered(habit, repeat = false)
        assertEquals(Due.AT, at.due)
        assertEquals(AtTime("07:45"), ScheduleDuePick.opening(null, at).picked(null))
        // The minutes typed for In are kept beside the At pick, as the pill keeps them while switching.
        assertEquals(45, at.inAmount)
    }

    @Test fun `a relative pick reopens on the same chip`() {
        val habit = ScheduleDuePick().apply { relative = ScheduleWhen.TOMORROW_MORNING }
            .remembered(ScheduleHabit(), repeat = false)
        assertEquals(ScheduleWhen.TOMORROW_MORNING, ScheduleDuePick.opening(null, habit).picked(null))
    }

    @Test fun `waiting for sessions leaves the timing habit as it was`() {
        val before = ScheduleHabit(Due.IN, inAmount = 20, inMinutes = true, repeat = true, target = target)
        val pick = ScheduleDuePick().apply { afterSessions = true }
        val newTarget = target.copy(model = null)
        assertEquals(before.copy(target = newTarget), pick.remembered(before, repeat = false, target = newTarget))
    }

    @Test fun `a typed amount that names no moment keeps the previous one`() {
        val pick = ScheduleDuePick().apply { custom = ScheduleDuePick.CustomDue.IN; delayText = "" }
        assertEquals(20, pick.remembered(ScheduleHabit(inAmount = 20), repeat = false).inAmount)
    }

    @Test fun `Usage's reset offer outranks the habit's pick but keeps its typed values`() {
        val habit = ScheduleHabit(Due.IN, inAmount = 12, inMinutes = true)
        val pick = ScheduleDuePick.opening(NewChatTarget("", AgentVendor.CLAUDE), habit)
        assertTrue(pick.atReset)
        assertNull(pick.custom)
        assertEquals("12", pick.delayText)
    }

    @Test fun `the create dialog opens on the remembered target while its agent and project are still there`() {
        val vendors = listOf(AgentVendor.CLAUDE, AgentVendor.CODEX)
        assertEquals(target, openingTarget(listOf("/other", "/work/plugin"), vendors, null, target))
        // A project the IDE closed gives way to the first open one; the picks stay.
        assertEquals(target.copy(projectPath = "/other"), openingTarget(listOf("/other"), vendors, null, target))
        // An agent the machine no longer offers is not aimed at.
        assertEquals(AgentVendor.CLAUDE, openingTarget(listOf("/other"), listOf(AgentVendor.CLAUDE), null, target).vendor)
        assertNull(openingTarget(listOf("/other"), listOf(AgentVendor.CLAUDE), null, target).model)
        // Usage's preset is the user's newest intent.
        val preset = NewChatTarget("", AgentVendor.CLAUDE, accountId = "personal")
        assertEquals("personal", openingTarget(listOf("/other"), vendors, preset, target).accountId)
    }
}
