package it.unibz.vincent.util

import org.jetbrains.annotations.Contract
import org.slf4j.LoggerFactory
import java.nio.ByteBuffer
import java.util.*
import java.util.function.BiFunction

/** Trim leading and trailing whitespace from given string.
 * If resulting string would be empty, return null instead.
 * If resulting string would be longer than maxLength, return only first maxLength characters.
 * Uses the same whitespace definition as [String.trim].  */
@Contract("null, _ -> null")
fun trimToNullAndShorten(str: String?, maxLength: Int): String? {
	val end = (str ?: return null).length
	var contentStartsAt = 0
	while (contentStartsAt < end && str[contentStartsAt] <= ' ') {
		contentStartsAt++
	}
	var contentEndsAt = minOf(end, contentStartsAt + maxLength) // Do not search full string if it is too long
	while (contentEndsAt > contentStartsAt && str[contentEndsAt - 1] <= ' ') {
		contentEndsAt--
	}
	return if (contentStartsAt == contentEndsAt) { // Trimmed to nothing
		null
	} else str.substring(contentStartsAt, contentEndsAt)
}

/** Convenience wrapper for [Runtime.addShutdownHook].  */
fun onShutdown(action: () -> Unit) {
	Runtime.getRuntime().addShutdownHook(Thread(action, "onShutdown($action)"))
}

fun <T> StringBuilder.appendList(list:List<T>, itemPrefix:String = "", itemSuffix:String = "", itemSeparator:String = ", ", lastItemSeparator:String = itemSeparator):StringBuilder {
	for (i in list.indices) {
		if (i != 0) {
			if (i == list.lastIndex) {
				append(lastItemSeparator)
			} else {
				append(itemSeparator)
			}
		}
		append(itemPrefix).append(list[i]).append(itemSuffix)
	}
	return this
}

operator fun Int.plus(b:Boolean):Int {
	return this + if (b) 1 else 0
}


private val HEX_DIGIT = "0123456789ABCDEF".toCharArray()

/** Append single hexadecimal digit to [this]. */
fun StringBuilder.appendHex(byte: Byte):StringBuilder {
	append(HEX_DIGIT[(byte.toInt() ushr 4) and 0xF])
	append(HEX_DIGIT[byte.toInt() and 0xF])
	return this
}

/** Append all remaining bytes as hex digits through [appendHex] */
fun StringBuilder.appendHex(byteBuffer:ByteBuffer):StringBuilder {
	while (byteBuffer.hasRemaining()) {
		appendHex(byteBuffer.get())
	}
	return this
}

/** Append all bytes as hex digits through [appendHex] */
fun StringBuilder.appendHex(byteBuffer:ByteArray):StringBuilder {
	for (byte in byteBuffer) {
		appendHex(byte)
	}
	return this
}

private fun Char.toAsciiUpperCase():Char {
	if (this in 'a'..'z') {
		return (this - 'a' + 'A'.toInt()).toChar()
	}
	return this
}

/** Retrieve the hexadecimal number stored in [this].
 * Amount of characters must be even.
 * @param startIndex index of first digit of the bytes
 * @param endIndex first index that is no longer a digit */
fun CharSequence.parseHex(startIndex:Int, endIndex:Int):ByteArray? {
	if ((endIndex - startIndex) % 2 != 0) {
		return null
	}
	val result = ByteArray((endIndex - startIndex) / 2)
	for ((resultI, i) in (startIndex until endIndex step 2).withIndex()) {
		val highDigit = HEX_DIGIT.indexOf(this[i].toAsciiUpperCase())
		val lowDigit = HEX_DIGIT.indexOf(this[i + 1].toAsciiUpperCase())
		if (highDigit < 0 || lowDigit < 0) {
			return null
		}
		result[resultI] = ((highDigit shl 4) or (lowDigit)).toByte()
	}
	return result
}

private fun ByteArray.b(index:Int):Long {
	return this[index].toLong() and 0xFFL
}

/** Get long value from the 8 bytes starting at [index], little endian. */
fun ByteArray.getLong(index:Int):Long {
	return b(index) or
			(b(index+1) shl 8) or
			(b(index+2) shl 16) or
			(b(index+3) shl 24) or
			(b(index+4) shl 32) or
			(b(index+5) shl 40) or
			(b(index+6) shl 48) or
			(b(index+7) shl 56)
}

/** Put long [value] to the 8 bytes starting at [index], little endian. */
fun ByteArray.putLong(index:Int, value:Long) {
	this[index] = value.toByte()
	this[index+1] = (value ushr 8).toByte()
	this[index+2] = (value ushr 16).toByte()
	this[index+3] = (value ushr 24).toByte()
	this[index+4] = (value ushr 32).toByte()
	this[index+5] = (value ushr 40).toByte()
	this[index+6] = (value ushr 48).toByte()
	this[index+7] = (value ushr 56).toByte()
}
