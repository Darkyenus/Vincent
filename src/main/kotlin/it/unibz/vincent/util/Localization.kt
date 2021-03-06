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
import kotlin.math.roundToInt

typealias LocaleStack = List<ULocale>

fun LocaleStack.defaultLocale():ULocale = this.firstOrNull() ?: ULocale.ENGLISH

/**
 *
 */
fun LocaleStack.l(template:String):String {
	return template
}

fun Instant.toHumanReadableTime(locale:LocaleStack, timeZone:ZoneId?, relative:Boolean? = null):String {
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
			RelativeDateTimeFormatter.getInstance(uLocale).format(time.roundToInt().toDouble(), timeUnit)
					?.takeUnless { it.isEmpty() }
					?.let { return it }
		}
	}

	val icuZone = JavaTimeZone(TimeZone.getTimeZone(timeZone), null)
	val dateFormat = DateFormat.getDateTimeInstance(Calendar.getInstance(icuZone, uLocale), DateFormat.FULL, DateFormat.SHORT, uLocale)
	return dateFormat.format(Date.from(this))
}