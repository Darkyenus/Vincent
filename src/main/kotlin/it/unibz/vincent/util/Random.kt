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