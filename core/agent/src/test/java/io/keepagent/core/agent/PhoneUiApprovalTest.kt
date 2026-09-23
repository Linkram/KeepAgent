package io.keepagent.core.agent

import io.keepagent.addonsapi.ToolPermission
import io.keepagent.core.events.EventBus
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PhoneUiApprovalTest {
    private val tool = ToolSpec("phone_ui", "Phone UI", ToolPermission.PHONE_UI, "{}")

    @Test
    fun generalNeverAskDoesNotBypassPhoneApproval() = runBlocking {
        val gate = ApprovalGate(EventBus(), { ApprovalMode.NEVER_ASK })
        val result = async { gate.request(tool, """{"action":"inspect"}""", "Inspect foreground app") }
        val pending = assertNotNull(gate.pending.first { it != null })
        assertFalse(gate.decide(true, requestId = "stale-request"))
        assertTrue(gate.decide(true, remember = true, requestId = pending.id))
        assertTrue(result.await())
        assertTrue(gate.consumePhoneUiPermit("""{"action":"inspect"}"""))
        assertFalse(gate.consumePhoneUiPermit("""{"action":"inspect"}"""))
        val second = async { gate.request(tool, """{"action":"click"}""", "Click target") }
        val next = assertNotNull(gate.pending.first { it != null })
        assertFalse(gate.decide(true, requestId = pending.id))
        assertTrue(gate.decide(false, requestId = next.id))
        assertFalse(second.await())
        assertFalse(gate.consumePhoneUiPermit("""{"action":"click"}"""))
    }

    @Test
    fun explicitFullAccessBypassesPhoneApproval() = runBlocking {
        val gate = ApprovalGate(EventBus(), { ApprovalMode.ASK }, phoneUiFullAccessProvider = { true })
        assertTrue(gate.request(tool, "{}", "Inspect foreground app"))
        assertTrue(gate.pending.value == null)
    }
}
