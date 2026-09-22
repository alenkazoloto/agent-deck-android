# Mobile user stories and acceptance coverage

These stories describe the shipped Android companion. The IDE remains the authority for running
agents. The phone must let a paired user reach Chats, Review, Schedule and Workspace without losing work,
including when Android restores a conversation or an external intent opens it.

## Executable journeys

`MobileUserStoriesE2eTest` launches the real `MainActivity`, Compose UI and `DeckViewModel` on an
Android device. It uses the production encrypted `SecureStore`, HTTPS certificate pinning and
`BridgeClient`. `StoryBridge` is an in-process TLS server implementing the IDE protocol with
synthetic sessions. It records requests and changes its responses after commands. No production
screen callbacks, navigation state or network client are replaced. The fixture extra used for
static screenshots is deliberately absent.

The restored-chat regression seeds exactly the state left by a previous process: a saved
conversation with no in-memory back stack. Its first assertion is that the visible Back button
exists; later assertions click it and visit every main destination. A unit test of the stack alone
cannot satisfy this story.

| ID | As a mobile user, I can… | Acceptance criteria | Device E2E method |
|---|---|---|---|
| M01 | Return from the current chat to my sessions and main destinations. | After a cold restored-chat launch, Back is visible and leads to Chats; Review (projecting the cached list even before the machine answers), Schedule and Workspace are reachable and can return to Chats. | `restoredConversationCanReturnToChatsAndEveryMainDestination` |
| M02 | Keep my draft when Android recreates the screen or I leave a chat. | Recreation retains text; Android Back returns to Chats; reopening the same chat retains the draft. | `restoredConversationSystemBackAndRecreationKeepExitAndDraft` |
| M03 | Find and read a specific session. | Search excludes a nonmatching session; clearing search restores it; opening a row loads that session's transcript. | `searchOpenAndReplyUsesRealBridgeAndClearsOnlyAcceptedDraft` |
| M04 | Send a reply and see it in the conversation. | Tapping Send posts the correct conversation key and prompt over HTTPS, with the model and mode the desk would send and an effort picked on the phone, refreshes the transcript and clears only the accepted draft. | `searchOpenAndReplyUsesRealBridgeAndClearsOnlyAcceptedDraft` |
| M05 | Recover from a refused send without retyping. | A bridge refusal is visible; the message is the one parked outgoing item, quoted word for word beside the conversation (not a second copy in the composer); Edit returns exactly that text to the composer and sending consumes it. | `refusedReplyPreservesDraftAndCanBeRetried` |
| M06 | Start a new chat without losing an unfinished task. | Back cancels the composer; reopening restores its task; Selecting another project, Codex, its model, effort and mode and — with two accounts — its account sends those exact choices; Start chat returns to the refreshed list. | `newChatCanBeCancelledResumedAndStarted` |
| M07 | Manage prompts scheduled for later. | Create posts a future due time on the picked agent, account and effort — Codex is offered with no Codex conversation on the machine, and an untouched mode is not sent; the queue shows the prompt; pause, resume and run-now issue the right commands; cancelling can be declined, then confirmed and removes the row. | `scheduledPromptCanBeCreatedPausedResumedRunAndCancelled` |
| M08 | Open pairing for another machine and cancel safely. | Workspace opens the additional-machine form; its Back to Workspace button works; Chats remains reachable. | `settingsAndAddMachineCanAlwaysReturnToChats` |
| M09 | Open a notification link without becoming trapped in a chat. | A conversation URI opens the transcript and provides a visible Back route to Chats. | `notificationDeepLinkHasVisibleExitAndSharedTextRemainsUnsent` |
| M10 | Share text into a chat and review it before sending. | An Android `ACTION_SEND` intent lets me choose a session; the shared text becomes a draft and causes no send request. | `notificationDeepLinkHasVisibleExitAndSharedTextRemainsUnsent` |
| M11 | Read earlier messages and jump to the newest message. | Scrolling away from the tail reveals an accessible down-arrow action; tapping it shows the newest turn and hides the action. | `latestMessageArrowReturnsFromHistoryToNewestTurn` |
| M12 | Pair by hand and disconnect this phone deliberately. | Entering host, port, code and certificate fingerprint completes real TLS pairing; dismissing the notification offer reaches Chats; declining unpair keeps the pairing; confirming returns to first-run pairing. | `firstPairingManualFormReachesChatsAndUnpairReturnsToPairing` |
| M13 | Stop a running agent or stop it before sending new instructions. | Stop reaches the selected session; Stop & send posts stop before send, with the new prompt. | `stopAndStopThenSendReachTheRunningConversationInOrder` |
| M14 | Use a suggested reply and edit long text without accidentally sending. | A quick reply only fills the draft; the fullscreen editor and Back retain text; leaving/reopening preserves it. | `quickReplyAndFullscreenEditorKeepTextUntilExplicitSend` |
| M15 | Answer an agent's question from its choices. | Selecting a live option posts the question key and chosen label to the answer route, without an ordinary send. With several questions or a multi-select one, each question keeps its own picks and one Send answers posts every question together; a refused answer can be re-sent with the picks kept. | `answerAgentQuestionUsesTheAnswerRoute`, `QuestionAnswersTest` |
| M16 | Customize appearance and switch machines without mixing drafts. | Theme, notification trigger and update-notice preferences survive Activity recreation; selecting another machine activates it and shows its own draft while retaining the first machine's draft; the root bar's machine picker switches back. | `settingsPersistAndSwitchingMachinesKeepsEachDraft` |
| M17 | Keep working with cached conversations when the machine is unreachable. | Cached text and draft remain readable; the saved page timestamp stays above the messages through pending/failed refresh; fresh page acceptance clears it; Back works. | `unreachableMachineKeepsCachedChatAndDraftNavigable` |
| M18 | Triage a busy session list. | Running scope excludes finished rows; All restores them; long-press snooze offers Undo, which restores the row. | `sessionScopesAndSnoozeUndoKeepTheListUsable` |
| M19 | Use Android Back while typing without losing my way. | With the real keyboard open, the first Back dismisses it; the next Back returns to Chats; reopening retains the draft. | `keyboardBackDismissesTypingThenReturnsToChats` |
| M20 | Navigate on a tablet or a wider window without being trapped in a chat or composer. | At 700dp and 1000dp widths the child keeps Back and the navigation rail; Back exits the child; all four destinations (badged or not) are on the rail, and Workspace and Schedule work directly from children; leaving New chat keeps its draft. | `wideLayoutsKeepNavigationAvailableInsideChatsAndComposers` |
| M21 | Resume the same passage after another chat or Android restart. | Stable turn/offset and follow-latest survive Back, recreation and process restart; machine scopes remain separate; visiting the tail manually clears unread output. | `ReadingContinuityE2eTest` |
| M22 | Retrieve earlier downloaded history. | Load earlier preserves the boundary turn, deduplicates overlap, and offers local retry/refresh on failure. | `ConversationBackE2eTest` history journeys |
| M24 | Name a project file from my phone without spelling its whole path. | Typing `@` plus a fragment offers the IDE's own index rows; accepting one rewrites the draft; a machine that does not advertise `files` never opens the popup, and `foo@bar.com` never does either. | `MentionDraftTest`, `ComposerAttachmentInteractionTest`, `MobileFileSearchWiringTest` |
| M25 | Send a photo of the thing I am asking about. | The paperclip appears only while the machine accepts photos; a picked photo is re-encoded under the cap, uploaded, shown as a chip the ✕ takes back, and reaches the agent as the desktop's own attachment line; a photo alone is sendable. | `PhotoEncoderTest`, `ComposerAttachmentInteractionTest`, `MobileAttachmentWiringTest`, golden `conversation-composer-photo.png` |
| M23 | Find a phrase in a long conversation. | Find states downloaded-source-text scope, reveals exact occurrences, steps matches and closes without losing the draft. | `ConversationFindE2eTest`, `ActivityFindE2eTest` |
| M24 | Read what a tool call returned without walking to the desk. | Opening a finished call shows its output and the command behind it; a cut body names the bytes missing and fetches the rest on request; a running call opens nothing; thinking is present but folded. | `ToolDetailInteractionTest` |
| M25 | Read and clear an agent's changes without walking to the desk. | A Changes tab lists the files it touched with their sizes; opening one shows its diff; ticking a file or all of them clears the IDE's checklist, and a still-running conversation says why the tick is withheld. | `ChangesTabTest`, `MobileReviewWiringTest` |
| M26 | Keep an instruction typed with no link, and see it land once. | A send with no link is kept, the composer says it is queued, and it is delivered when the link returns; a refusal or a delivery the app cannot confirm parks with Retry / Edit / Discard and says the machine may already have it; a retry the machine recognises schedules one run. | `OutgoingQueueTest`, `OutgoingDrainTest`, `MobileSendDedupeWiringTest` |
| M28 | Skim a long conversation instead of flicking through it. | An Outline action appears once a chat has two prompts; the sheet ranks prompts as chapters with the agent's tool runs and answers under them, says how many prompts and tool calls are in view, and tapping a row lands on that turn. | `OutlineTest`, `OutlineInteractionTest` |
| M29 | Re-ask something I asked before, from a clean context. | A prompt row in the outline starts a new chat carrying that prompt's whole text, appended to anything already drafted there; an answer row offers nothing. | `OutlineTest`, `OutlineInteractionTest` |
| M30 | Read code on a phone. | Keywords, strings and comments are coloured in a chat's code fences and in a diff — one tokenizer, ten languages, plain for anything else; Settings › Reading wraps long lines in both, and numbers a diff's. | `MarkdownTest`, goldens `conversation-portrait.png`, `conversation-changes-reading.png` |
| M31 | Keep my place while agents work. | A conversation pulls down to re-ask the machine, offers a jump to the oldest message it holds and to the latest, and the session list does not re-order under my thumb while I aim at a row. | `FleetGroupingTest`, `OutlineInteractionTest` |
| M32 | See what a blocked chat waits on, and what my next message will run with. | A chat the machine holds waiting on me names the wait (tool permission, answer, plan approval) in place of "working…", with no spinner; with a draft, one line spells the model, effort, mode and any chosen Fast/Thinking, and opens a sheet that changes them for that chat's next send. | `ConversationWaitingTest`, `RunOptionPickersInteractionTest`, golden `conversation-composer-pills.png` |
| M27 | Know what my agents have cost and whether my plans can still run them. | Settings › Usage shows today/7d/30d/all-time spend and the costliest models with the machine's own estimate marks, plus every open plan window per account with its percent and the machine's own reset wording; an account with no measurement says why, an indexing machine says its totals will grow, and a machine without the `usage` capability offers no Usage section. | `MobileUsageTest`, `UsageRangesTest`, `MobileUsageWiringTest`, `UsageScreenTest`, goldens `usage.png`, `usage-unmeasured.png`, `settings-usage.png` |


## Running the journeys

`ConversationBackE2eTest` adds the cross-chat notification journey: read A, open B through an
Android VIEW intent, then press the visible Back control. Distinct per-key HTTPS responses
verify A's own messages, draft, metadata and run state, plus a fresh read for A. Companion
cases cover offline return and delayed responses. The test restores its launch intent during
teardown because ActivityScenario tracks lifecycle events by that original intent.

Use an isolated emulator or test phone: the suite clears this application's pairings and drafts
before each test. The wide-layout test temporarily changes logical display size and restores the
previous override in `finally`. The suite never connects to the operator's IDE or real agents.

```sh
cd mobile
./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=dev.agentdeck.companion.MobileUserStoriesE2eTest
```

The suite uses semantic text and accessible action names, not pixel coordinates. Assertions cover
both screen outcomes and commands received by the fake IDE. The Gradle connected-test report is the
run result; the existence of this matrix is not evidence that a run passed. The restored-chat
test also writes `restored-chat.png` and `restored-chat-back-to-chats.png` under the test app's
external files directory and copies them to `/data/local/tmp/agent-deck-story-evidence/`
for `adb pull` after Gradle uninstalls the test target. Wide-layout evidence uses the same directory.

## External integration boundaries

The device suite covers the app side of these flows. Separate integration checks are needed for
systems the in-process fake IDE cannot truthfully certify:

- A real IDE must accept and execute send, stop, answers and scheduled commands. These device
  tests prove the request and the resulting app behavior against the documented protocol.
- Camera QR capture needs a camera and a physical/displayed QR; the manual route exercises the
  common pairing submission and certificate check. QR decode has separate JVM tests.
- Speech recognition is supplied by an Android recognizer; cancellation, permission UI and
  recognition quality depend on the installed provider. Draft merging has separate JVM tests.
- Notification delivery while the app is closed needs a UnifiedPush distributor, relay and real
  IDE. Conversation URI handling is exercised here; delivery is not inferred from opening a URI.
- Launcher shortcuts, home widgets, notification-channel settings and file sharing depend on the
  launcher/OS/provider. Their incoming link/text contracts are covered; provider-specific UI needs
  a device integration run.
- APK download, digest verification and Android's install approval require an actual release and
  package installer. Update visibility and verification have JVM/UI tests; these journeys do not
  install an APK over the instrumentation target.

External checks are release acceptance work, not silently counted as passed by the loopback suite.
