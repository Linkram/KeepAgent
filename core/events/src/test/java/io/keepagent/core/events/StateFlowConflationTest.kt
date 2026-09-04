package io.keepagent.core.events

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Documents the MutableStateFlow conflation trap that froze the Add-ons tab
 * (2026-09-03 device test): republishing a list of the *same mutated
 * instances* is structurally equal to the previous value, so the update is
 * dropped and observers (Compose) never recompose.
 *
 * The fix (AddonManager.refreshRecords) publishes copies — new instances —
 * so every refresh is structurally different and always propagates.
 */
class StateFlowConflationTest {

    data class Rec(var status: String, val tools: MutableList<String> = mutableListOf())

    @Test
    fun inPlaceMutationIsDroppedByConflation() = runBlocking {
        val rec = Rec("a")
        val flow = MutableStateFlow<List<Rec>>(listOf(Rec("init")))
        val seen = mutableListOf<String>()
        val job = launch(Dispatchers.Default) {
            flow.collect { seen += it.firstOrNull()?.status ?: "EMPTY" }
        }
        delay(30)
        flow.value = listOf(rec) // propagates (init -> a)
        delay(30)
        rec.status = "b"
        flow.value = listOf(rec) // same instance — structurally equal — dropped
        delay(60)
        job.cancel()
        assertEquals(listOf("init", "a"), seen, "conflation drops in-place mutations")
    }

    @Test
    fun publishingCopiesAlwaysPropagates() = runBlocking {
        val rec = Rec("a")
        val flow = MutableStateFlow<List<Rec>>(listOf(Rec("init")))
        val seen = mutableListOf<String>()
        val job = launch(Dispatchers.Default) {
            flow.collect { seen += it.firstOrNull()?.status ?: "EMPTY" }
        }
        delay(30)
        flow.value = listOf(rec.copy())
        delay(30)
        rec.status = "b"
        flow.value = listOf(rec.copy(tools = rec.tools.toMutableList())) // fresh instances
        delay(60)
        job.cancel()
        assertEquals(listOf("init", "a", "b"), seen, "copied instances always propagate")
    }
}
