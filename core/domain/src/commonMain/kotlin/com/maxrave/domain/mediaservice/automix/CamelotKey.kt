package com.maxrave.domain.mediaservice.automix

import kotlin.math.abs

/** A key on the Camelot wheel: 1..12 and A (minor) or B (major). */
data class CamelotKey(
    val number: Int,
    val isMinor: Boolean,
) {
    override fun toString(): String = "$number${if (isMinor) "A" else "B"}"

    /** Steps around the wheel plus one for switching between minor and major. */
    fun distanceTo(other: CamelotKey): Int {
        val diff = abs(number - other.number)
        return minOf(diff, 12 - diff) + if (isMinor != other.isMinor) 1 else 0
    }

    /** Same key, one step either way, or the relative major/minor: the mixes that never clash. */
    fun isCompatibleWith(other: CamelotKey): Boolean =
        if (isMinor == other.isMinor) distanceTo(other) <= 1 else number == other.number

    companion object {
        private val MINOR_BY_PITCH = intArrayOf(5, 12, 7, 2, 9, 4, 11, 6, 1, 8, 3, 10)
        private val MAJOR_BY_PITCH = intArrayOf(8, 3, 10, 5, 12, 7, 2, 9, 4, 11, 6, 1)

        /** From Tidal's spelling ("FSharp", "Bb") and scale ("MINOR"); null when unreadable. */
        fun parse(
            key: String?,
            scale: String?,
        ): CamelotKey? {
            if (key.isNullOrBlank()) return null
            val normalized =
                key
                    .trim()
                    .replace("Sharp", "#", ignoreCase = true)
                    .replace("Flat", "b", ignoreCase = true)
                    .replaceFirstChar { it.uppercaseChar() }
            val semitone =
                when (normalized) {
                    "C" -> 0
                    "C#", "Db" -> 1
                    "D" -> 2
                    "D#", "Eb" -> 3
                    "E" -> 4
                    "F" -> 5
                    "F#", "Gb" -> 6
                    "G" -> 7
                    "G#", "Ab" -> 8
                    "A" -> 9
                    "A#", "Bb" -> 10
                    "B" -> 11
                    else -> return null
                }
            val minor = scale?.uppercase()?.contains("MIN") == true
            return CamelotKey(if (minor) MINOR_BY_PITCH[semitone] else MAJOR_BY_PITCH[semitone], minor)
        }
    }
}
