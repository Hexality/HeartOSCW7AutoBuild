package red.kitsu.heartosc

import kotlin.math.roundToInt

internal object VrcoscHeartrateParameters {
    private const val PREFIX = "/avatar/parameters/VRCOSC/Heartrate"

    const val CONNECTED = "$PREFIX/Connected"
    const val VALUE = "$PREFIX/Value"
    const val NORMALISED = "$PREFIX/Normalised"
    const val AVERAGE = "$PREFIX/Average"
    const val BEAT = "$PREFIX/Beat"

    // Kept for compatibility with avatars made for older VRCOSC heartrate modules.
    const val ENABLED = "$PREFIX/Enabled"
    const val UNITS = "$PREFIX/Units"
    const val TENS = "$PREFIX/Tens"
    const val HUNDREDS = "$PREFIX/Hundreds"

    const val AVERAGE_PERIOD_MS = 10_000L
    const val RECEIVING_TIMEOUT_MS = 30_000L
    private const val NORMALISED_UPPER_BOUND = 240f

    fun normalised(bpm: Int): Float = bpm / NORMALISED_UPPER_BOUND

    fun legacyDigits(bpm: Int): Triple<Float, Float, Float> {
        val value = bpm.coerceIn(0, 999)
        return Triple(
            (value % 10) / 10f,
            ((value / 10) % 10) / 10f,
            ((value / 100) % 10) / 10f
        )
    }
}

internal data class VrcoscHeartRateValues(
    val bpm: Int,
    val normalised: Float,
    val average: Int,
    val units: Float,
    val tens: Float,
    val hundreds: Float
)

internal class VrcoscHeartRateTracker {
    private val samples = ArrayDeque<HeartRateSample>()
    private var latestSampleAtMillis: Long? = null

    @Synchronized
    fun record(sample: HeartRateSample): VrcoscHeartRateValues {
        latestSampleAtMillis = sample.receivedAtMillis
        samples.addLast(sample)
        while (samples.firstOrNull()?.receivedAtMillis?.let {
                it + VrcoscHeartrateParameters.AVERAGE_PERIOD_MS <= sample.receivedAtMillis
            } == true
        ) {
            samples.removeFirst()
        }

        val average = samples.map { it.bpm }.average().roundToInt()
        val (units, tens, hundreds) = VrcoscHeartrateParameters.legacyDigits(sample.bpm)
        return VrcoscHeartRateValues(
            bpm = sample.bpm,
            normalised = VrcoscHeartrateParameters.normalised(sample.bpm),
            average = average,
            units = units,
            tens = tens,
            hundreds = hundreds
        )
    }

    @Synchronized
    fun isReceiving(nowMillis: Long): Boolean = latestSampleAtMillis?.let {
        it + VrcoscHeartrateParameters.RECEIVING_TIMEOUT_MS >= nowMillis
    } == true

    @Synchronized
    fun millisecondsUntilStale(nowMillis: Long): Long = latestSampleAtMillis?.let {
        (it + VrcoscHeartrateParameters.RECEIVING_TIMEOUT_MS - nowMillis).coerceAtLeast(0L) + 1L
    } ?: 0L

    @Synchronized
    fun clear() {
        samples.clear()
        latestSampleAtMillis = null
    }
}
