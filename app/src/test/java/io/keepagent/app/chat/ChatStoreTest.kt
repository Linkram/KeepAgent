package io.keepagent.app.chat

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ChatStoreTest {
    @Test fun activeCheckpointIsClassifiedAsInterrupted() {
        assertEquals(TurnRecovery.INTERRUPTED_ERROR, TurnRecovery.failureFor(true))
        assertTrue(StoredTurn(userText = "continue", inProgress = true).inProgress)
    }

    @Test fun legacyTurnDefaultsToSettled() {
        assertFalse(StoredTurn(userText = "done").inProgress)
        assertEquals(null, TurnRecovery.failureFor(false))
    }
}
