package it.unibz.vincent.util

import com.ibm.icu.util.ULocale
import java.time.Duration
import java.util.concurrent.TimeUnit
import kotlin.math.abs

typealias LocaleStack = List<ULocale>

/**
 *
 */
fun LocaleStack.l(template:String):String {
	return template
}

private val SECONDS_IN_DAY = TimeUnit.DAYS.toSeconds(1)
private val SECONDS_IN_HOUR = TimeUnit.HOURS.toSeconds(1)
private val SECONDS_IN_MINUTE = TimeUnit.MINUTES.toSeconds(1)

/** Adds a space (separator) if needed. */
private fun StringBuilder.sep():StringBuilder {
	if (length > 0) {
		append(' ')
	}
	return this
}

/** Format duration to a human readable string.
 * Ignores the duration sign. */
fun Duration.toHumanReadableString(locale:LocaleStack):String {
	val result = StringBuilder()
	var seconds = abs(this.seconds)

	val days = seconds / SECONDS_IN_DAY
	seconds %= SECONDS_IN_DAY
	if (days > 0) {
		result.sep().append(days).append(if (days == 1L) " day" else " days")
	}

	val hours = seconds / SECONDS_IN_HOUR
	seconds %= SECONDS_IN_HOUR
	if (hours > 0) {
		result.sep().append(hours).append(if (hours == 1L) " hour" else " hours")
	}

	val minutes = seconds / SECONDS_IN_MINUTE
	seconds %= SECONDS_IN_MINUTE
	if (minutes > 0) {
		result.sep().append(minutes).append(if (minutes == 1L) " minute" else " minutes")
	}

	if (seconds > 0) {
		result.sep().append(seconds).append(if (seconds == 1L) " second" else " seconds")
	}

	if (result.isEmpty()) {
		result.append("now")
	}

	return result.toString()
}