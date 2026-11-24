package com.maxrave.simpmusic.expect

import platform.Foundation.NSNumber
import platform.Foundation.NSNumberFormatter
import platform.Foundation.NSNumberFormatterDecimalStyle
import kotlin.math.abs
import kotlin.math.roundToInt

// ponytail: always a '.' decimal point; NSNumberFormatter can localise it if anyone notices.
actual fun formatOneDecimal(value: Float): String {
    val tenths = (value * 10).roundToInt()
    val sign = if (tenths < 0) "-" else ""
    return "$sign${abs(tenths) / 10}.${abs(tenths) % 10}"
}

actual fun formatGrouped(value: Int?): String {
    if (value == null) return "null"
    val formatter = NSNumberFormatter().apply { numberStyle = NSNumberFormatterDecimalStyle }
    return formatter.stringFromNumber(NSNumber(int = value)) ?: value.toString()
}

/**
 * First-strong-character rule (UAX #9 P2/P3), which is what Bidi's base direction is. A text is
 * left-to-right throughout when its base is and it holds no right-to-left or Arabic-number
 * character, which would open a right-to-left run.
 */
actual fun textBidi(
    text: String,
    rtlFallback: Boolean,
): TextBidi {
    var base: Boolean? = null
    var hasRtl = false
    for (char in text) {
        val rtl = char.isRightToLeft()
        if (rtl) hasRtl = true
        if (base == null) {
            if (rtl && char.isLetter()) {
                base = false
            } else if (!rtl && char.isLetter()) {
                base = true
            }
        }
        if (base != null && hasRtl) break
    }
    val baseIsLeftToRight = base ?: !rtlFallback
    return TextBidi(baseIsLeftToRight = baseIsLeftToRight, isLeftToRight = baseIsLeftToRight && !hasRtl)
}

// Hebrew, Arabic, Syriac, Thaana, NKo, Samaritan, Mandaic and their presentation forms.
private fun Char.isRightToLeft(): Boolean =
    code in 0x0590..0x08FF || code in 0xFB1D..0xFDFF || code in 0xFE70..0xFEFF

actual fun desktopOsName(): String = "iOS"

// Kotlin/Native frames are plain strings ("... kfun:com.example.Foo#bar(){} + 12 (/src/Foo.kt:3:5)"),
// so the first one naming [classPrefix] is cut down to "com.example.Foo#bar" plus the source
// position when the binary carries debug info.
@OptIn(kotlin.experimental.ExperimentalNativeApi::class)
actual fun Throwable.firstStackFrame(classPrefix: String): String? {
    val frame = getStackTrace().firstOrNull { it.contains("kfun:$classPrefix") } ?: return null
    val symbol = frame.substringAfter("kfun:").substringBefore('(')
    val position = frame.substringAfterLast('(', "").substringBefore(')').substringAfterLast('/')
    return if (position.isEmpty() || position == frame) symbol else "$symbol:$position"
}
