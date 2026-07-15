package red.kitsu.heartosc

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
