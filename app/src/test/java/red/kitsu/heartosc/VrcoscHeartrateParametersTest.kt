package red.kitsu.heartosc

import org.junit.Assert.assertEquals
import org.junit.Test

class VrcoscHeartrateParametersTest {
    @Test
    fun `uses VRCOSC heartrate parameter paths`() {
        assertEquals("/avatar/parameters/VRCOSC/Heartrate/Connected", VrcoscHeartrateParameters.CONNECTED)
        assertEquals("/avatar/parameters/VRCOSC/Heartrate/Value", VrcoscHeartrateParameters.VALUE)
        assertEquals("/avatar/parameters/VRCOSC/Heartrate/Normalised", VrcoscHeartrateParameters.NORMALISED)
        assertEquals("/avatar/parameters/VRCOSC/Heartrate/Average", VrcoscHeartrateParameters.AVERAGE)
        assertEquals("/avatar/parameters/VRCOSC/Heartrate/Beat", VrcoscHeartrateParameters.BEAT)
        assertEquals("/avatar/parameters/VRCOSC/Heartrate/Enabled", VrcoscHeartrateParameters.ENABLED)
        assertEquals("/avatar/parameters/VRCOSC/Heartrate/Units", VrcoscHeartrateParameters.UNITS)
        assertEquals("/avatar/parameters/VRCOSC/Heartrate/Tens", VrcoscHeartrateParameters.TENS)
        assertEquals("/avatar/parameters/VRCOSC/Heartrate/Hundreds", VrcoscHeartrateParameters.HUNDREDS)
    }

    @Test
    fun `normalises bpm using VRCOSC default bounds`() {
        assertEquals(0f, VrcoscHeartrateParameters.normalised(0))
        assertEquals(0.5f, VrcoscHeartrateParameters.normalised(120))
        assertEquals(1f, VrcoscHeartrateParameters.normalised(240))
        assertEquals(1.25f, VrcoscHeartrateParameters.normalised(300))
    }

    @Test
    fun `maps digits to legacy float parameters`() {
        assertEquals(
            Triple(0.8f, 0.9f, 0f),
            VrcoscHeartrateParameters.legacyDigits(98)
        )
        assertEquals(
            Triple(0.5f, 0.4f, 0.3f),
            VrcoscHeartrateParameters.legacyDigits(345)
        )
    }
}
