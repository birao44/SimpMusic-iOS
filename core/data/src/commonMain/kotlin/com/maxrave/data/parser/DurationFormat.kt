package com.maxrave.data.parser

/** `mm:ss`, as `"%02d:%02d".format(minutes, seconds)` gave; String.format does not exist on Kotlin/Native. */
internal fun formatMinutesSeconds(totalSeconds: Int): String =
    "${(totalSeconds / 60).toString().padStart(2, '0')}:${(totalSeconds % 60).toString().padStart(2, '0')}"
