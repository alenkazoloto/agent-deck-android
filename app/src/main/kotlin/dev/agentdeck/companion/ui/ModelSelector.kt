package dev.agentdeck.companion.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.github.claudeagents.core.AgentVendor
import com.github.claudeagents.core.mobile.MobileHello
import com.github.claudeagents.core.mobile.MobileProtocol

/**
 * Which model a phone-started prompt names.
 *
 * One composable for both callers — the new-chat screen and the scheduled-prompt dialog post
 * the same `MobileSendRequest`, and two pickers over one field is how the two drift.
 *
 * [MobileSendRequest.model] has been on the wire since v1 and the bridge has always forwarded
 * it; what was missing was the machine *telling* the phone which models exist, which is
 * [MobileProtocol.Capability.MODELS].
 */
@Composable
fun ModelSelector(
    hello: MobileHello?,
    vendor: AgentVendor,
    /** The model in use, or null for the machine's own default. */
    selected: String?,
    onSelect: (String?) -> Unit,
    modifier: Modifier = Modifier,
    /**
     * What the pill says before its value. Defaults to naming itself, because the dialog that
     * draws it has no room for headings — the new-chat screen passes null, where a heading
     * already says "Model" and the pill would otherwise read "Model Opus 5".
     */
    prefix: String? = "Model",
) {
    val rows = ModelRows.of(hello, vendor, selected)
    if (rows.isEmpty()) return
    Selector(
        options = rows,
        selected = selected,
        onSelect = onSelect,
        modifier = modifier,
        prefix = prefix,
    )
}

/**
 * The rows [ModelSelector] offers — a pure function, because all three of its rules are things
 * a screenshot cannot be asked about.
 *
 * - **"Default" is first, and it is `null`.** A null model is *no `model` field on the wire*,
 *   which is what every phone-started chat did before this control existed and is the only way
 *   to say "whatever the IDE is set to". A ladder of named models alone makes that unsayable.
 * - **A model in use that the catalogue does not list is shown as itself.** `memory/wiring.md`:
 *   a catalogue ported from a vendor's binary is dated evidence, so a model the machine no
 *   longer offers — shipped after this plugin's build, or hidden in Settings › Chat models
 *   since the pick — must not be silently rewritten to something the user did not choose.
 * - **No capability, no control.** A machine that does not advertise
 *   [MobileProtocol.Capability.MODELS] never named a model, so there is nothing to pick from
 *   and the caller renders nothing — not an empty dropdown.
 */
object ModelRows {

    const val DEFAULT_LABEL = "Default"

    fun of(hello: MobileHello?, vendor: AgentVendor, inUse: String?): List<SelectorOption<String?>> {
        val advertised = hello != null && MobileProtocol.Capability.MODELS in hello.capabilities
        val catalogue = if (advertised) hello.models[vendor].orEmpty() else emptyList()
        // A model in use is a row even where nothing was advertised: dropping it would leave the
        // screen claiming the run is on the machine's default when the request still names it.
        val stranger = inUse?.takeIf { slug -> catalogue.none { it.slug == slug } }
        if (catalogue.isEmpty() && stranger == null) return emptyList()
        return listOf(SelectorOption<String?>(DEFAULT_LABEL, null)) +
            catalogue.map { SelectorOption<String?>(it.label, it.slug) } +
            listOfNotNull(stranger?.let { SelectorOption<String?>(it, it) })
    }
}
