package it.unibz.vincent.util

import org.jetbrains.annotations.Contract
import org.slf4j.LoggerFactory
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
