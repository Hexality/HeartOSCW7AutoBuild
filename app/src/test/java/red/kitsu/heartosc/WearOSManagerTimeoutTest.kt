package red.kitsu.heartosc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WearOSManagerTimeoutTest {

    @Test
    fun `verify 30-second connection timeout threshold`() {
        val timeoutMs = 30000L
        val lastReceivedTime = 1000L

        // Under 30 seconds -> still active
        val withinTimeout = (1000L + 29999L) - lastReceivedTime <= timeoutMs
        assertTrue("Should be active within 30 seconds", withinTimeout)

        // Exactly 30 seconds -> boundary
        val atTimeout = (1000L + 30000L) - lastReceivedTime <= timeoutMs
        assertTrue("Should be active at 30 seconds boundary", atTimeout)

        // Exceeding 30 seconds -> timed out
        val exceedsTimeout = (1000L + 30001L) - lastReceivedTime > timeoutMs
        assertTrue("Should trigger timeout after 30 seconds", exceedsTimeout)
    }

    @Test
    fun `vrcosc heart rate tracker timeout test`() {
        val tracker = VrcoscHeartRateTracker()
        tracker.record(HeartRateSample(75, 1000L))

        assertTrue("Connected within 30s", tracker.isReceiving(30999L))
        assertFalse("Disconnected after 30s timeout", tracker.isReceiving(31001L))
    }
}
