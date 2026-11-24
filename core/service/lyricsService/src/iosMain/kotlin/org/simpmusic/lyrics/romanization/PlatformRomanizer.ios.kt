package org.simpmusic.lyrics.romanization

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents
import platform.CoreFoundation.CFLocaleCreate
import platform.CoreFoundation.CFRangeMake
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.CFStringCreateWithCString
import platform.CoreFoundation.CFStringTokenizerAdvanceToNextToken
import platform.CoreFoundation.CFStringTokenizerCopyCurrentTokenAttribute
import platform.CoreFoundation.CFStringTokenizerCreate
import platform.CoreFoundation.CFStringTokenizerGetCurrentTokenRange
import platform.CoreFoundation.kCFStringEncodingUTF8
import platform.CoreFoundation.kCFStringTokenizerAttributeLatinTranscription
import platform.CoreFoundation.kCFStringTokenizerTokenNone
import platform.CoreFoundation.kCFStringTokenizerUnitWord
import platform.Foundation.CFBridgingRelease
import platform.Foundation.NSString
import platform.Foundation.NSStringTransformLatinToHiragana
import platform.Foundation.NSStringTransformMandarinToLatin
import platform.Foundation.create
import platform.Foundation.stringByApplyingTransform

/**
 * Japanese and Chinese romanization on iOS, through Foundation instead of kuromoji / pinyin4j.
 *
 *  - **Japanese**: CFStringTokenizer with the `ja` locale is Apple's own morphological analyzer — it
 *    splits the line into words and gives each one its Latin transcription in context, which is
 *    what kuromoji does on Android. To keep the romaji style identical to Android, each kanji
 *    word's reading is turned back into kana and the whole line runs through the shared
 *    [KanaRomanizer], exactly like Android's katakana-reading path. A reading that does not survive
 *    the round trip keeps Apple's Latin text.
 *  - **Chinese**: ICU's Mandarin→Latin transform gives space-separated, lowercase pinyin with tone
 *    marks — the output format the pinyin4j path on Android is configured for.
 *
 * Neither needs a dictionary download (see the iOS RomanizationDictionaryPack). Both stay inside
 * the "null means show nothing" contract: any failure answers null.
 */
@OptIn(ExperimentalForeignApi::class)
internal actual object PlatformRomanizer {
    private val multipleSpaces = Regex(" {2,}")

    actual fun japanese(line: String): String? =
        runCatching {
            japaneseReading(line)?.let { reading -> KanaRomanizer.romanize(reading).replace(multipleSpaces, " ").trim() }
        }.getOrNull()

    actual fun chinese(line: String): String? =
        runCatching {
            NSString
                .create(string = line)
                .stringByApplyingTransform(NSStringTransformMandarinToLatin, reverse = false)
                ?.replace(multipleSpaces, " ")
                ?.trim()
        }.getOrNull()

    /**
     * The line with every word that contains kanji replaced by its kana reading (or, failing the
     * round trip, by Apple's Latin transcription); everything else — kana, Latin, and the spaces
     * and punctuation between words — exactly as written.
     */
    private fun japaneseReading(line: String): String? {
        val cfLine = CFStringCreateWithCString(null, line, kCFStringEncodingUTF8) ?: return null
        val cfLocaleId = CFStringCreateWithCString(null, "ja", kCFStringEncodingUTF8)
        val locale = CFLocaleCreate(null, cfLocaleId)
        val tokenizer =
            CFStringTokenizerCreate(null, cfLine, CFRangeMake(0, line.length.toLong()), kCFStringTokenizerUnitWord, locale)
        try {
            if (tokenizer == null) return null
            val builder = StringBuilder()
            var cursor = 0
            while (CFStringTokenizerAdvanceToNextToken(tokenizer) != kCFStringTokenizerTokenNone) {
                val (start, length) =
                    CFStringTokenizerGetCurrentTokenRange(tokenizer).useContents { location.toInt() to length.toInt() }
                if (start < cursor || length <= 0 || start + length > line.length) continue
                // Whatever the tokenizer skipped (spaces, punctuation) stays as it was written.
                builder.append(line, cursor, start)
                val surface = line.substring(start, start + length)
                if (surface.any { it.isHan() }) {
                    val latin =
                        CFStringTokenizerCopyCurrentTokenAttribute(tokenizer, kCFStringTokenizerAttributeLatinTranscription)
                            ?.let { CFBridgingRelease(it) as? String }
                    builder.append(latin?.let { kanaFor(it) ?: it } ?: surface)
                } else {
                    builder.append(surface)
                }
                cursor = start + length
            }
            builder.append(line, cursor, line.length)
            return builder.toString()
        } finally {
            tokenizer?.let { CFRelease(it) }
            locale?.let { CFRelease(it) }
            cfLocaleId?.let { CFRelease(it) }
            CFRelease(cfLine)
        }
    }

    /** Apple's romaji back to hiragana, or null when anything Latin is left over after the transform. */
    private fun kanaFor(latin: String): String? {
        val kana =
            NSString
                .create(string = latin)
                .stringByApplyingTransform(NSStringTransformLatinToHiragana, reverse = false)
                ?: return null
        return kana.takeIf { converted -> converted.none { it in 'a'..'z' || it in 'A'..'Z' } }
    }
}
