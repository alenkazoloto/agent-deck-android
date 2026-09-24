package dev.agentdeck.companion.fixture

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import dev.agentdeck.companion.DeckFixtureHook

/**
 * Installs [DeckFixtures] into the `main` seam before any Activity starts.
 *
 * A `ContentProvider` declared in `src/debug/AndroidManifest.xml` is the standard way a debug
 * variant runs code at process start without a debug `Application` class — the same mechanism
 * androidx App Startup uses. Nothing calls it; the framework does, and only in a debug build,
 * so `DeckFixtureHook.provider` is null in anything shipped.
 */
class FixtureInstaller : ContentProvider() {

    override fun onCreate(): Boolean {
        DeckFixtureHook.provider = DeckFixtures::byName
        DeckFixtureHook.commitSheet = DeckFixtures::commitSheet
        DeckFixtureHook.worktree = DeckFixtures::worktree
        DeckFixtureHook.worktreeSheet = DeckFixtures::worktreeSheet
        DeckFixtureHook.reviewSheet = DeckFixtures::reviewSheet
        DeckFixtureHook.repositorySheet = DeckFixtures::repositorySheet
        DeckFixtureHook.commitStaged = DeckFixtures::commitStaged
        DeckFixtureHook.scope = DeckFixtures::scope
        DeckFixtureHook.revertSheet = DeckFixtures::revertSheet
        DeckFixtureHook.rewind = DeckFixtures::rewind
        DeckFixtureHook.notes = DeckFixtures::notes
        DeckFixtureHook.aiReview = DeckFixtures::aiReview
        DeckFixtureHook.context = DeckFixtures::contextSheet
        DeckFixtureHook.sessionDirs = DeckFixtures::sessionDirsSheet
        DeckFixtureHook.sessionSpend = DeckFixtures::sessionSpendSheet
        DeckFixtureHook.workingDiff = DeckFixtures::workingDiff
        return true
    }

    override fun query(u: Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, s: String?, a: Array<out String>?): Int = 0
    override fun update(uri: Uri, v: ContentValues?, s: String?, a: Array<out String>?): Int = 0
}
