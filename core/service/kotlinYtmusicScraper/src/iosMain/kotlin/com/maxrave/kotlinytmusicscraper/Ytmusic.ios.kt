package com.maxrave.kotlinytmusicscraper

import platform.Foundation.NSLocale
import platform.Foundation.countryCode
import platform.Foundation.currentLocale
import platform.Foundation.languageCode

// Same shape as java.util.Locale on Android/JVM: an ISO 3166 region ("US") and an ISO 639 language ("en").
actual fun getCountry(): String = NSLocale.currentLocale.countryCode ?: "US"

actual fun getLanguage(): String = NSLocale.currentLocale.languageCode
