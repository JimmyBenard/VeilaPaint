package studio.nuvora.veila.studio

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import studio.nuvora.veila.core.RecordingState

class AtelierDisplayPolicyTest {
    @Test
    fun revealPercentageIsVisibleOnlyWhenNoRecordingIsActive() {
        assertFalse(shouldShowRevealProgress(RecordingState.IDLE))
        assertTrue(shouldShowRevealProgress(RecordingState.IDLE, enabled = true))
        assertFalse(shouldShowRevealProgress(RecordingState.RECORDING))
        assertFalse(shouldShowRevealProgress(RecordingState.PAUSED))
        assertFalse(shouldShowRevealProgress(RecordingState.STOPPING))
    }
}
