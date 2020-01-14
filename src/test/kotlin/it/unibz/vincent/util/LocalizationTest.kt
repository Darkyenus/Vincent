package it.unibz.vincent.util

import com.ibm.icu.util.ULocale
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 *
 */
class LocalizationTest {

	@Test
	fun timeFormattingTest() {
		// Non formal test

		val englishLocale = listOf(ULocale.ENGLISH)
		val czechLocale = listOf(ULocale.forLanguageTag("cs"))
		val germanLocale = listOf(ULocale.GERMAN)

		val locales = listOf(
			englishLocale,
			czechLocale,
			germanLocale
		)

		fun printTime(time:Instant) {
			println("------------ $time -----------")
			for (locale in locales) {
				println(time.toHumanReadableTime(locale))
			}
		}

		val now = Instant.now().plusSeconds(13)
		printTime(now)
		printTime(now.plus(10, ChronoUnit.SECONDS))
		printTime(now.plus(10, ChronoUnit.MINUTES))
		printTime(now.plus(10, ChronoUnit.HOURS))
		printTime(now.plus(10, ChronoUnit.DAYS))
		printTime(now.plus(3650, ChronoUnit.DAYS))
		printTime(now.minus(10, ChronoUnit.SECONDS))
		printTime(now.minus(10, ChronoUnit.MINUTES))
		printTime(now.minus(10, ChronoUnit.HOURS))
		printTime(now.minus(10, ChronoUnit.DAYS))
		printTime(now.minus(3650, ChronoUnit.DAYS))
	}

}