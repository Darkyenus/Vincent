package it.unibz.vincent.util

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import kotlin.system.measureTimeMillis

class PasswordTest {

	@Test
	fun simplePassword() {
		val myPassword = "hunter2".toByteArray()
		val encodedPassword = hashPassword(myPassword)
		assertEquals(HASHED_PASSWORD_SIZE, encodedPassword.size)
		assertTrue(checkPassword(myPassword, encodedPassword))

		val badPassword = "hunter3".toByteArray()
		assertFalse(checkPassword(badPassword, encodedPassword))
	}

	@Test
	fun speedTest() {
		val millis = measureTimeMillis {
			hashPassword("hello".toByteArray())
		}
		// Parameters are chosen to take ~100ms on standard desktop machine.
		// Something is wrong if we finish an order of magnitude faster.
		// (This is the whole point of password hashing algorithms.)
		assertTrue(millis > 10, "Hashing parameters are too easy, because hashing finished too soon ($millis ms). Or you have a very fast computer.")
	}

}