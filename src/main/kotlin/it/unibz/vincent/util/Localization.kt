package it.unibz.vincent.util

import com.ibm.icu.impl.JavaTimeZone
import com.ibm.icu.text.DateFormat
import com.ibm.icu.text.RelativeDateTimeFormatter
import com.ibm.icu.util.Calendar
import com.ibm.icu.util.ULocale
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.util.*
import java.util.concurrent.TimeUnit
import kotlin.math.abs

typealias LocaleStack = List<ULocale>

fun LocaleStack.defaultLocale():ULocale = this.firstOrNull() ?: ULocale.ENGLISH

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

fun Instant.toHumanReadableTime(locale:LocaleStack, relative:Boolean? = null):String {
	val uLocale = locale.defaultLocale()

	if (relative != false) {
		val now = Instant.now()
		val duration = Duration.between(now, this)
		val absSeconds = abs(duration.seconds)
		val time:Double
		val timeUnit:RelativeDateTimeFormatter.RelativeDateTimeUnit

		if (absSeconds < TimeUnit.MINUTES.toSeconds(1L)) {
			time = duration.seconds.toDouble()
			timeUnit = RelativeDateTimeFormatter.RelativeDateTimeUnit.SECOND
		} else if (absSeconds < TimeUnit.HOURS.toSeconds(1L)) {
			time = duration.seconds.toDouble() / TimeUnit.MINUTES.toSeconds(1L).toDouble()
			timeUnit = RelativeDateTimeFormatter.RelativeDateTimeUnit.MINUTE
		} else if (absSeconds < TimeUnit.DAYS.toSeconds(1L)) {
			time = duration.seconds.toDouble() / TimeUnit.HOURS.toSeconds(1L).toDouble()
			timeUnit = RelativeDateTimeFormatter.RelativeDateTimeUnit.HOUR
		} else if (absSeconds < TimeUnit.DAYS.toSeconds(1L) * 365) {
			time = duration.seconds.toDouble() / TimeUnit.DAYS.toSeconds(1L).toDouble()
			timeUnit = RelativeDateTimeFormatter.RelativeDateTimeUnit.DAY
		} else if (relative == true) {
			// Forced to be relative
			time = duration.seconds.toDouble() / (TimeUnit.DAYS.toSeconds(1L).toDouble() * 365.25)
			timeUnit = RelativeDateTimeFormatter.RelativeDateTimeUnit.YEAR
		} else {
			// We don't have to be relative, use absolute time instead
			time = Double.NaN
			timeUnit = RelativeDateTimeFormatter.RelativeDateTimeUnit.SECOND
		}

		if (java.lang.Double.isFinite(time)) {
			RelativeDateTimeFormatter.getInstance(uLocale).format(Math.round(time).toDouble(), timeUnit)
					?.takeUnless { it.isEmpty() }
					?.let { return it }
		}
	}

	// TODO(jp): User provided time zone offset
	val zone = ZoneId.systemDefault()
	val icuZone = JavaTimeZone(TimeZone.getTimeZone(zone), null)
	val dateFormat = DateFormat.getDateTimeInstance(Calendar.getInstance(icuZone, uLocale), DateFormat.FULL, DateFormat.SHORT, uLocale)
	return dateFormat.format(Date.from(this))
}