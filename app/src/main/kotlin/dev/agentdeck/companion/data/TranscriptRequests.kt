package dev.agentdeck.companion.data

/** Main-thread owner: coalesce run ticks without starving a slow transcript response. */
internal class TranscriptRequests {
    class Request(val machineId: String, val key: String) {
        var refreshAgain = false
    }

    private var active: Request? = null

    fun begin(machineId: String, key: String): Request? {
        active?.takeIf { it.machineId == machineId && it.key == key }?.let {
            it.refreshAgain = true
            return null
        }
        return Request(machineId, key).also { active = it }
    }

    fun owns(request: Request): Boolean = active === request

    fun finish(request: Request): Boolean {
        if (!owns(request)) return false
        active = null
        return request.refreshAgain
    }

    fun reset() { active = null }
}
