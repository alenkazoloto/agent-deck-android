package dev.agentdeck.companion.ui

import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.github.claudeagents.core.mobile.MobilePreviewAction
import com.github.claudeagents.core.mobile.MobilePreviewProject
import com.github.claudeagents.core.mobile.MobilePreviewProjects
import com.github.claudeagents.core.mobile.MobilePreviewView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * What the Preview screen asks of the machine. One bundle so a test drives it without a view model;
 * each call answers a [Result] whose failure message is already the sentence to show.
 */
class PreviewLink(
    val projects: suspend () -> Result<MobilePreviewProjects>,
    /** The state, and a picture when `frame` is true. */
    val view: suspend (project: String, frame: Boolean) -> Result<MobilePreviewView>,
    val act: suspend (MobilePreviewAction) -> Result<MobilePreviewView>,
)

/** How often the picture is asked for again while the screen is showing and nothing is in flight. */
internal const val PREVIEW_REFRESH_MS = 2_500L

/**
 * The ordering rules between the polling refresh and the reader's own intents, kept plain so a JVM test
 * can hold them. A refresh that started before an intent (or while one is in flight) answers with the page
 * *before* it, so applying it would put the old picture and zoom back and wipe the intent's sentence.
 */
internal class PreviewFeed {
    private var epoch = 0
    private var pending = 0

    /** The epoch a refresh starts in, or null while an intent is in flight — a refresh then would only race it. */
    fun beginRefresh(): Int? = if (pending > 0) null else epoch

    /** A refresh's answer counts only if no intent began or ran since it started. */
    fun keepsRefresh(startedIn: Int): Boolean = pending == 0 && startedIn == epoch

    fun beginIntent() {
        epoch++
        pending++
    }

    fun endIntent() {
        pending--
    }
}

/**
 * Settings › Resources › "Preview": the desk's Preview tab as the machine draws it — a picture of the
 * page in the IDE's own browser, with the desk's address, frame, colour scheme and zoom as controls
 * and one gesture on the picture, "Add an element to the chat".
 *
 * Nothing of the page runs here: the phone is shown what the machine's browser paints and sends typed
 * intents back, so the desk's cookies and sign-ins never leave it and every control moves the desk's
 * own selector. The picture is asked for again while the screen is open, and never while an intent is
 * in flight, so the picture a tap is read against is the one the reader is looking at. The machine's
 * refusals — an address of another site, a page that is not showing, the grant not being ticked — are
 * sentences under the picture, in its words.
 *
 * The page can be scrolled a screenful up or down (the machine's own script, so the phone names only a direction). Adding an element is one armed tap: press it,
 * tap the element on the picture, and the machine adds the desk's own annotation sentence to the chat draft there. It reads the element, it never clicks it.
 *
 * Under a grant of its own, and only while the tab is on a page of this machine (`MobilePreviewState.canInteract`), the page can also be used: "Tap the page" arms
 * taps that click what is under the finger, a line of text is typed into the field the page has focused, and Enter, Tab, Backspace and Esc are pressed. Typed text
 * stays in its field until the machine has taken it. A drag on the picture is not offered; the page scrolls by its buttons.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PreviewSheet(link: PreviewLink, onDismiss: () -> Unit, refreshMs: Long = PREVIEW_REFRESH_MS) {
    val current by rememberUpdatedState(link)
    val scope = rememberCoroutineScope()
    var projects by remember { mutableStateOf<List<MobilePreviewProject>?>(null) }
    var project by rememberSaveable { mutableStateOf<String?>(null) }
    var view by remember { mutableStateOf<MobilePreviewView?>(null) }
    var failure by remember { mutableStateOf<String?>(null) }
    var armed by remember { mutableStateOf(false) }
    var clicking by remember { mutableStateOf(false) }
    var actNotice by remember { mutableStateOf<String?>(null) }
    // True while the picture on screen is one a refresh could not replace: a tap on it would be read against a page the machine has moved on from.
    var carried by remember { mutableStateOf(false) }
    val feed = remember { PreviewFeed() }
    var typed by rememberSaveable { mutableStateOf("") }
    // Not saved with the screen and dropped with the project: it may be a password, and one Send must not reach another project's page.
    var typing by remember(project) { mutableStateOf("") }
    var typedFor by rememberSaveable { mutableStateOf<String?>(null) }

    fun accept(answer: Result<MobilePreviewView>, intent: Boolean, tapped: Boolean = false) {
        answer.onSuccess { v ->
            failure = null
            // No picture from a page that stayed silent keeps the last one, marked [carried]. A tap's answer carries none on
            // purpose (the picture it was made on is still the one on screen) unless the tap was stale and needs a fresh one.
            if (v.frame != null) carried = false else if (!(tapped && v.notice != MobilePreviewAction.STALE)) carried = v.state.open
            view = v.copy(frame = v.frame ?: view?.frame, notice = if (intent) null else v.notice)
            if (intent) actNotice = v.notice
            if (carried) { armed = false; clicking = false }
            // The reader's own typing in the address field is not overwritten by the page moving on.
            if (v.state.url.isNotEmpty() && typedFor != v.state.url) {
                if (typed == typedFor.orEmpty()) typed = v.state.url
                typedFor = v.state.url
            }
        }.onFailure {
            failure = it.message ?: "The machine did not answer."
            armed = false
            clicking = false
        }
    }

    LaunchedEffect(Unit) {
        current.projects().onSuccess { answer ->
            projects = answer.projects
            if (project == null) project = answer.projects.singleOrNull()?.path
        }.onFailure { failure = it.message ?: "The machine did not answer." }
    }
    // One loop per chosen project: it ends with the screen, pauses in the background, is replaced by a different project,
    // and never runs beside an intent of the reader's.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(Unit) {
        snapshotFlow { project }.filterNotNull().distinctUntilChanged().collectLatest { chosen ->
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    val startedIn = feed.beginRefresh()
                    if (startedIn != null) {
                        val answer = current.view(chosen, true)
                        if (feed.keepsRefresh(startedIn)) accept(answer, intent = false)
                    }
                    delay(refreshMs)
                }
            }
        }
    }

    fun act(action: MobilePreviewAction, taken: (Result<MobilePreviewView>) -> Unit = {}) {
        feed.beginIntent()
        actNotice = null
        scope.launch {
            val answer = current.act(action)
            accept(answer, intent = true, tapped = action.action == MobilePreviewAction.ANNOTATE)
            taken(answer)
            feed.endIntent()
        }
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Surface(Modifier.fillMaxSize().testTag("preview-dialog"), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()) {
                Row(Modifier.fillMaxWidth().padding(end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    DeckIconButton("Back", Icons.AutoMirrored.Filled.ArrowBack, onClick = {
                        // Back from a chosen project returns to the list only when there is a list to return to.
                        if (project != null && (projects?.size ?: 0) > 1) { project = null; view = null; armed = false; clicking = false } else onDismiss()
                    })
                    Text("Preview", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                val chosen = project
                when {
                    chosen == null -> ProjectList(projects, failure) { project = it.path }
                    else -> Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 16.dp)) {
                        val state = view?.state
                        OutlinedTextField(
                            value = typed,
                            onValueChange = { typed = it },
                            label = { Text("Address") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                            keyboardActions = KeyboardActions(onGo = { act(MobilePreviewAction(chosen, MobilePreviewAction.OPEN, url = typed)) }),
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp).testTag("preview-address"),
                        )
                        if (state?.open == true) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                DeckIconButton("Reload the page", Icons.Filled.Refresh, onClick = { act(MobilePreviewAction(chosen, MobilePreviewAction.RELOAD)) })
                                DeckIconButton("Zoom out", DeckIcons.Minus, onClick = { act(MobilePreviewAction(chosen, MobilePreviewAction.ZOOM, value = "-1")) })
                                TextButton(
                                    onClick = { act(MobilePreviewAction(chosen, MobilePreviewAction.ZOOM_RESET)) },
                                    modifier = Modifier.semantics { contentDescription = "Reset zoom to 100%, now ${state.zoomPercent}%" },
                                ) { Text("${state.zoomPercent}%") }
                                DeckIconButton("Zoom in", Icons.Filled.Add, onClick = { act(MobilePreviewAction(chosen, MobilePreviewAction.ZOOM, value = "1")) })
                                DeckIconButton(
                                    if (armed) "Cancel adding an element" else "Add an element to the chat",
                                    Icons.Filled.Search,
                                    onClick = { armed = !armed; clicking = false },
                                    modifier = Modifier.testTag("preview-annotate"),
                                )
                            }
                            // A page is taller than the picture, so scrolling it is the one gesture besides the armed tap; older machines omit it.
                            if (state.canScroll) Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("Scroll the page", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                DeckIconButton("Scroll the page up", Icons.Filled.KeyboardArrowUp, onClick = { act(MobilePreviewAction(chosen, MobilePreviewAction.SCROLL, value = MobilePreviewAction.SCROLL_UP)) })
                                DeckIconButton("Scroll the page down", Icons.Filled.KeyboardArrowDown, onClick = { act(MobilePreviewAction(chosen, MobilePreviewAction.SCROLL, value = MobilePreviewAction.SCROLL_DOWN)) })
                            }
                            if (state.canInteract) {
                                FilterChip(
                                    selected = clicking,
                                    onClick = { clicking = !clicking; armed = false },
                                    label = { Text("Tap the page") },
                                    modifier = Modifier.padding(top = 4.dp).testTag("preview-click"),
                                )
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    OutlinedTextField(
                                        value = typing,
                                        onValueChange = { typing = it.filter { c -> !c.isISOControl() }.take(MobilePreviewAction.MAX_TYPED) },
                                        label = { Text("Type on the page") },
                                        singleLine = true,
                                        keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, imeAction = ImeAction.Send),
                                        keyboardActions = KeyboardActions(onSend = {
                                            if (typing.isNotEmpty()) act(MobilePreviewAction(chosen, MobilePreviewAction.TYPE, value = typing)) { if (it.getOrNull()?.notice == null && it.isSuccess) typing = "" }
                                        }),
                                        modifier = Modifier.weight(1f).testTag("preview-type"),
                                    )
                                    DeckIconButton("Type this on the page", Icons.AutoMirrored.Filled.Send, enabled = typing.isNotEmpty(), onClick = {
                                        act(MobilePreviewAction(chosen, MobilePreviewAction.TYPE, value = typing)) { if (it.getOrNull()?.notice == null && it.isSuccess) typing = "" }
                                    }, modifier = Modifier.testTag("preview-type-send"))
                                }
                                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    MobilePreviewAction.KEYS.forEach { key ->
                                        val label = if (key == "Escape") "Esc" else key
                                        TextButton(onClick = { act(MobilePreviewAction(chosen, MobilePreviewAction.KEY, value = key)) }, modifier = Modifier.semantics { contentDescription = "Press $label on the page" }) { Text(label) }
                                    }
                                }
                            }
                            Choices("Frame", state.viewports.map { it.id to it.label }, state.viewport) {
                                act(MobilePreviewAction(chosen, MobilePreviewAction.VIEWPORT, value = it))
                            }
                            Choices("Appearance", state.appearances.map { it.id to it.label }, state.appearance) {
                                act(MobilePreviewAction(chosen, MobilePreviewAction.APPEARANCE, value = it))
                            }
                        }
                        if (armed) Note("Tap the element on the picture to add it to the chat.")
                        val clickArmed = clicking && state?.canInteract == true
                        if (clickArmed) Note("Tap the page on the picture to click there.")
                        val frame = view?.frame
                        // Above the picture, not under it: a page is taller than the phone, so a sentence below it is off the screen.
                        (actNotice ?: view?.notice ?: state?.unavailable.takeIf { frame == null } ?: failure)?.let {
                            Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 12.dp).testTag("preview-notice"))
                        }
                        if (frame != null) {
                            PreviewPicture(frame.jpegBase64, frame.width, frame.height, state?.url.orEmpty(), (armed || clickArmed) && !carried && failure == null) { x, y ->
                                if (clickArmed) {
                                    act(MobilePreviewAction(chosen, MobilePreviewAction.CLICK, x = x, y = y, rev = frame.rev))
                                } else {
                                    armed = false
                                    act(MobilePreviewAction(chosen, MobilePreviewAction.ANNOTATE, x = x, y = y, rev = frame.rev))
                                }
                            }
                        }
                        if (view == null && failure == null) Note("Asking the machine for the page…")
                    }
                }
            }
        }
    }
}

@Composable
private fun ProjectList(projects: List<MobilePreviewProject>?, failure: String?, onChoose: (MobilePreviewProject) -> Unit) {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        when {
            failure != null -> Note(failure)
            projects == null -> Note("Reading the machine's open projects…")
            projects.isEmpty() -> Note("No project is open in the IDE. Open one there to preview its page.")
            else -> {
                Text("Whose preview?", style = MaterialTheme.typography.titleSmall)
                projects.forEach { OutlinedButton(onClick = { onChoose(it) }, modifier = Modifier.fillMaxWidth()) { Text(it.name.ifBlank { it.path }) } }
            }
        }
    }
}

/** A row of the desk's own choices, every one visible and the current one marked. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Choices(title: String, options: List<Pair<String, String>>, selected: String, onPick: (String) -> Unit) {
    if (options.isEmpty()) return
    Column(Modifier.padding(top = 4.dp)) {
        Text(title, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEach { (id, label) ->
                FilterChip(selected = id == selected, onClick = { if (id != selected) onPick(id) }, label = { Text(label.substringBefore(" — ")) })
            }
        }
    }
}

/**
 * The machine's picture, decoded off the main thread, drawn at the page's own aspect. A tap is
 * reported only while [armed], as fractions of the picture, which is what the machine resolves.
 */
@Composable
private fun PreviewPicture(jpegBase64: String, width: Int, height: Int, url: String, armed: Boolean, onTap: (Double, Double) -> Unit) {
    val bitmap by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, jpegBase64) {
        value = withContext(Dispatchers.Default) {
            runCatching {
                val bytes = Base64.decode(jpegBase64, Base64.DEFAULT)
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
            }.getOrNull()
        }
    }
    var size by remember { mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }
    val shown = bitmap
    if (shown == null) {
        Note("Drawing the page…")
        return
    }
    val tap = rememberUpdatedState(onTap)
    Box(Modifier.padding(top = 12.dp)) {
        Image(
            bitmap = shown,
            contentDescription = "The page at $url",
            contentScale = ContentScale.FillWidth,
            modifier = Modifier
                .fillMaxWidth()
                .onSizeChanged { size = it }
                .pointerInput(armed) {
                    if (armed) detectTapGestures { at ->
                        if (size.width > 0 && size.height > 0) tap.value(at.x.toDouble() / size.width, at.y.toDouble() / size.height)
                    }
                }
                .testTag("preview-picture"),
        )
    }
}

@Composable
private fun Note(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 8.dp))
}
