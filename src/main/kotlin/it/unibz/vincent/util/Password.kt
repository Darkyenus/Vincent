package it.unibz.vincent.util

import com.lambdaworks.crypto.SCrypt
import java.text.Normalizer
import kotlin.experimental.or
import kotlin.experimental.xor

/**
 * Utility for password hashing.
 *
 * Resources:
 * [NIST Guidelines](https://pages.nist.gov/800-63-3/sp800-63b.html#sec5)
 */

/** Bytes of a raw password, as entered by the user. */
typealias RawPassword = ByteArray

/** These should be impossible to enter into the form, so trim them. They could only appear as a mistake or something. */
private val INVALID_PASSWORD_CHARACTERS = Regex("[\n\r\u0000]")

fun String.toRawPassword():RawPassword {
	return this
			.replace(INVALID_PASSWORD_CHARACTERS, "")
			.let { Normalizer.normalize(it, Normalizer.Form.NFKC) }
			.toByteArray(Charsets.UTF_8)
}

/**
 * Contains derived hash + metadata in following order:
 * [0] = [CURRENT_VERSION]
 * [1] = N
 * [2] = r
 * [3] = p
 * [4] = salt size
 * [5 - 5+salt size] = salt
 * [5+salt size - 5+salt size+hash size] = hash
 */
typealias HashedPassword = ByteArray

private const val CURRENT_VERSION:Byte = 1
private const val DEFAULT_N_POW:Byte = 16
private const val DEFAULT_R:Byte = 8
private const val DEFAULT_P:Byte = 1
private const val DEFAULT_SALT_SIZE:Byte = 16
private const val DEFAULT_KEY_SIZE:Byte = 32

const val HASHED_PASSWORD_SIZE = 5 + DEFAULT_SALT_SIZE + DEFAULT_KEY_SIZE

/** Hash given password and return hashed+salted result, including metadata. */
fun hashPassword(password:RawPassword):ByteArray {
	val salt = secureRandomBytes(DEFAULT_SALT_SIZE.toInt())

	val derived = SCrypt.scrypt(password, salt, 1 shl DEFAULT_N_POW.toInt(), DEFAULT_R.toInt(), DEFAULT_P.toInt(), DEFAULT_KEY_SIZE.toInt())

	var off = 0
	val derivedWithMetadata = ByteArray(HASHED_PASSWORD_SIZE)
	derivedWithMetadata[off++] = CURRENT_VERSION
	derivedWithMetadata[off++] = DEFAULT_N_POW
	derivedWithMetadata[off++] = DEFAULT_R
	derivedWithMetadata[off++] = DEFAULT_P
	derivedWithMetadata[off++] = DEFAULT_SALT_SIZE
	assert(off == 5)
	salt.copyInto(derivedWithMetadata, off)
	off += salt.size
	derived.copyInto(derivedWithMetadata, off)
	off += derived.size
	assert(off == derivedWithMetadata.size)

	return derivedWithMetadata
}

/** Check previous result of [hashPassword], [stored], against a new [passwordToCheck]. */
fun checkPassword(passwordToCheck:RawPassword, stored:HashedPassword):Boolean {
	val version = stored[0]
	if (version != CURRENT_VERSION) {
		throw IllegalArgumentException("Could not check password - version ($version) is not supported")
	}
	val nPow = stored[1]
	val r = stored[2]
	val p = stored[3]
	val saltSize = stored[4]
	val keyOffset = 5 + saltSize
	val keySize = stored.size - keyOffset
	val salt = stored.copyOfRange(5, keyOffset)

	val expectedHash = SCrypt.scrypt(passwordToCheck, salt, 1 shl nPow.toInt(), r.toInt(), p.toInt(), keySize)

	// NOTE: Constant speed comparison
	var result:Byte = 0
	for (i in expectedHash.indices) {
		result = result or (expectedHash[i] xor stored[keyOffset + i])
	}
	return result == 0.toByte()
}
