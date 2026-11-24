package com.maxrave.simpmusic.expect

// Small text helpers whose JVM version (Android and Desktop) is a java.* API with no common
// equivalent. The JVM actuals are the exact calls the shared code used to make.

/** `String.format("%.1f", value)`: one decimal, written the default locale's way. */
expect fun formatOneDecimal(value: Float): String

/** `"%,d".format(value)`: digits grouped the default locale's way, "null" for null. */
expect fun formatGrouped(value: Int?): String

/** What `java.text.Bidi` resolves for a piece of text. */
class TextBidi(
    /** `Bidi.baseIsLeftToRight()`. */
    val baseIsLeftToRight: Boolean,
    /** `Bidi.isLeftToRight()`: every character is left-to-right, base direction included. */
    val isLeftToRight: Boolean,
)

/** `java.text.Bidi(text, DIRECTION_DEFAULT_RIGHT_TO_LEFT or DIRECTION_DEFAULT_LEFT_TO_RIGHT)`. */
expect fun textBidi(
    text: String,
    rtlFallback: Boolean,
): TextBidi

/** The `os.name` system property, which is what Desktop reports as its OS. */
expect fun desktopOsName(): String

/** `"class.method:line"` of the first stack frame whose class starts with [classPrefix], or null. */
expect fun Throwable.firstStackFrame(classPrefix: String): String?
