package com.maxrave.simpmusic.expect

import java.text.Bidi

actual fun formatOneDecimal(value: Float): String = String.format("%.1f", value)

actual fun formatGrouped(value: Int?): String = "%,d".format(value)

actual fun textBidi(
    text: String,
    rtlFallback: Boolean,
): TextBidi {
    val bidi = Bidi(text, if (rtlFallback) Bidi.DIRECTION_DEFAULT_RIGHT_TO_LEFT else Bidi.DIRECTION_DEFAULT_LEFT_TO_RIGHT)
    return TextBidi(baseIsLeftToRight = bidi.baseIsLeftToRight(), isLeftToRight = bidi.isLeftToRight)
}

actual fun desktopOsName(): String = System.getProperty("os.name") ?: "jvm"

actual fun Throwable.firstStackFrame(classPrefix: String): String? =
    stackTrace.firstOrNull { it.className.startsWith(classPrefix) }?.let { "${it.className}.${it.methodName}:${it.lineNumber}" }
