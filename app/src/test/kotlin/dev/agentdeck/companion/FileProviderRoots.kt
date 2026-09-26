package dev.agentdeck.companion

import androidx.core.content.FileProvider
import java.lang.reflect.Modifier

/**
 * `FileProvider` caches each authority's roots for the life of the process, which on a device is
 * one cache dir; Robolectric gives every test a new one, so a later test's file would be "outside
 * any configured root". Clearing that cache makes each test resolve its own.
 */
internal fun forgetFileProviderRoots() {
    FileProvider::class.java.declaredFields
        .filter { Modifier.isStatic(it.modifiers) && Map::class.java.isAssignableFrom(it.type) }
        .forEach { field ->
            field.isAccessible = true
            val cache = field.get(null) as MutableMap<*, *>
            synchronized(cache) { cache.clear() }
        }
}
