package it.unibz.vincent.util

import org.slf4j.LoggerFactory
import java.security.NoSuchAlgorithmException
import java.security.SecureRandom
import kotlin.system.measureTimeMillis

private val LOG = LoggerFactory.getLogger("Random")

private val secureRandom: SecureRandom = try {
	SecureRandom.getInstance("SHA1PRNG")
} catch (e:NoSuchAlgorithmException) {
	SecureRandom()
}.apply {
	// The only strong instance ever, because they are extremely slow on some systems (like Debian)
	val ms = measureTimeMillis {
		val secureNativeRandom = try {
			// Strong secure random is often slow, try the non-blocking version first
			SecureRandom.getInstance("NativePRNGNonBlocking")
		} catch (e:NoSuchAlgorithmException) {
			SecureRandom.getInstanceStrong()
		}
		setSeed(secureNativeRandom.generateSeed(32))
	}
	if (ms > 100) {
		LOG.warn("Secure seeding took {} ms", ms)
	}
}

/** Returns [amount] of random bytes in an array of the same size.
 * Thread safe. */
fun secureRandomBytes(amount:Int):ByteArray {
	val bytes = ByteArray(amount)
	secureRandom.nextBytes(bytes)
	return bytes
}

/** A set of characters which are reasonably easy to distinguish from each other and can be used in passwords. */
private const val PASSWORD_CHARS = "qwertzuiopasdfghjkyxcvbnm1234567890.:_?!QWERTZUPASDFGHJKLYXCVBNM"

/** Generate a random, reasonably strong password. */
fun generateRandomPassword():CharSequence {
	val sb = StringBuilder()
	for (i in 0 until 4) {
		if (i != 0) {
			sb.append('-')
		}
		for (j in 0 until 3) {
			sb.append(PASSWORD_CHARS[secureRandom.nextInt(PASSWORD_CHARS.length)])
		}
	}
	return sb
}