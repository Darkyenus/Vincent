package it.unibz.vincent

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test

/**
 *
 */
class GuestCodeTest {

	private fun roundTripGuestCode(originalAccountId:Long) {
		val guestCode = accountIdToGuestCode(originalAccountId)
		val roundTripAccountId = guestCodeToAccountId(guestCode)
		assertNotNull(roundTripAccountId)
		assertEquals(originalAccountId, roundTripAccountId)
		println("$originalAccountId: $guestCode")
	}

	@Test
	fun guestCodeTest() {
		roundTripGuestCode(0L)
		roundTripGuestCode(1L)
		roundTripGuestCode(2L)
		roundTripGuestCode(3L)
		roundTripGuestCode(4L)
		roundTripGuestCode(-1L)
		roundTripGuestCode(-2L)
		roundTripGuestCode(-3L)
		roundTripGuestCode(12321434L)
		roundTripGuestCode(1234123412341234123L)
		roundTripGuestCode(-1234123412341234123L)
		roundTripGuestCode(Long.MAX_VALUE)
		roundTripGuestCode(Long.MAX_VALUE-1)
		roundTripGuestCode(Long.MAX_VALUE-2)
		roundTripGuestCode(Long.MIN_VALUE)
		roundTripGuestCode(Long.MIN_VALUE+1)
		roundTripGuestCode(Long.MIN_VALUE+2)
	}

}