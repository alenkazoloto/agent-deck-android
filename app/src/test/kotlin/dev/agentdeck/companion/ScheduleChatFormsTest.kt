package dev.agentdeck.companion

import com.github.claudeagents.core.mobile.MobileProtocol
import com.github.claudeagents.core.mobile.MobileScheduleDependencySelection
import dev.agentdeck.companion.data.ScheduleChatForm
import dev.agentdeck.companion.data.ScheduleChatForms
import dev.agentdeck.companion.data.ScheduleHabit
import dev.agentdeck.companion.data.ScheduleHabit.Due
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** mobile-todo Scheduling form memory: the desk's per-chat `FormDraft` and dependency draft, per chat on the phone. */
class ScheduleChatFormsTest {
    private fun reread(forms: ScheduleChatForms) =
        ScheduleChatForms.fromJson(MobileProtocol.parseObject(forms.toJson().toString()))

    @Test fun `a chat's form survives its own JSON, its dependency picks included`() {
        val forms = ScheduleChatForms().with(
            "chat-1",
            ScheduleChatForm(ScheduleHabit(Due.IN, inAmount = 30, inMinutes = true, repeat = true), listOf(MobileScheduleDependencySelection("s1", true))),
        ).with("chat-2", ScheduleChatForm(ScheduleHabit()))
        assertEquals(forms, reread(forms))
        assertNull(reread(forms)["chat-3"])
    }

    @Test fun `only the newest chats are kept`() {
        var forms = ScheduleChatForms()
        repeat(ScheduleChatForms.MAX_CHATS + 5) { forms = forms.with("chat-$it", ScheduleChatForm(ScheduleHabit(repeat = true))) }
        forms = forms.with("chat-10", ScheduleChatForm(ScheduleHabit()))
        assertEquals(ScheduleChatForms.MAX_CHATS, forms.forms.size)
        assertNull(forms["chat-4"])
        assertEquals(true, forms["chat-5"]?.habit?.repeat)
        assertEquals("a chat written again is the newest, not the next to go", "chat-10", forms.forms.keys.last())
        assertEquals(ScheduleChatForms.MAX_CHATS, reread(forms).forms.size)
    }

    @Test fun `stored garbage drops the entry instead of failing the dialog`() {
        val hostile = MobileProtocol.parseObject(
            """{"a":7,"b":{"habit":"soon","dependencies":[1,{"id":""},{"id":"ok"},"x"]},"c":{"dependencies":{}}}""",
        )
        val forms = ScheduleChatForms.fromJson(hostile)
        assertNull(forms["a"])
        assertEquals(ScheduleChatForm(ScheduleHabit(), listOf(MobileScheduleDependencySelection("ok"))), forms["b"])
        assertEquals(ScheduleChatForm(ScheduleHabit()), forms["c"])
        assertEquals(ScheduleChatForms(), ScheduleChatForms.fromJson(null))
    }
}
