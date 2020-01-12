package it.unibz.vincent.util

/**
 *
 */
class XmlBuilder(private val indent:Boolean) {

	private val sb = StringBuilder()

	private var whitespaceState = WHITESPACE_SKIP
	private var hadContent = false

	private val tagStack = ArrayList<String>()
	private val level
		get() = tagStack.size

	val characters:CharSequence
		get() = sb

	private fun appendLevel(newLine:Boolean):StringBuilder {
		val sb = sb
		if (!indent) {
			if (whitespaceState == WHITESPACE_PENDING) {
				whitespaceState = WHITESPACE_NONE
				sb.append(' ')
			}
			return sb
		}

		val indent = "  "
		sb.ensureCapacity(indent.length * level)
		val last = sb.lastIndex
		if (last >= 0 && sb[last] != '\n') {
			// There is some leftover content on the line
			if (newLine) {
				// New line requested, create one
				sb.append('\n')
			} else {
				// No new line requested, so this is fine, no indent needed
				return sb
			}
		}

		// We are at the start of a new line
		for (i in 0 until level) {
			sb.append(indent)
		}
		return sb
	}

	fun beginTag(tag:String, vararg attributes:Pair<String, Any?> = NO_ATTRIBUTES) {
		beginTag_begin(tag)
		for ((key, value) in attributes) {
			beginTag_attr(key, (value ?: continue).toString())
		}
		beginTag_end()
	}

	/** Low-level API */
	fun beginTag_begin(tag:String) {
		appendLevel(true).append('<').append(tag)
		tagStack.add(tag)
	}

	/** Low-level API */
	fun beginTag_attr(name:String, value:String) {
		val sb = sb.append(' ').append(name).append('=').append('"')
		for (c in value) {
			val escape = xmlEscape(c)
			if (escape != null) {
				sb.append(escape)
			} else {
				sb.append(c)
			}
		}
		sb.append('"')
	}

	/** Low-level API */
	fun beginTag_end() {
		sb.append('>')
		whitespaceState = WHITESPACE_SKIP
	}

	fun endTag() {
		val tag = tagStack.removeAt(tagStack.lastIndex)
		appendLevel(newLine=!hadContent).append('<').append('/').append(tag).append('>')
		whitespaceState = WHITESPACE_SKIP
		hadContent = false
	}

	fun content(text:CharSequence, escape:Boolean = true) {
		hadContent = true
		val sb = appendLevel(false)
		var whitespaceState = whitespaceState
		for (c in text) {
			val whitespace = when (c) {
				' ', '\n', '\t' -> true
				else -> false
			}

			if (whitespace) {
				if (whitespaceState == WHITESPACE_NONE) {
					whitespaceState = WHITESPACE_PENDING
				}
			} else {
				if (whitespaceState == WHITESPACE_PENDING) {
					sb.append(' ')
				}
				whitespaceState = WHITESPACE_NONE

				if (escape) {
					val escaped = xmlEscape(c)
					if (escaped != null) {
						sb.append(escaped)
					} else {
						sb.append(c)
					}
				} else {
					sb.append(c)
				}
			}
		}
		this@XmlBuilder.whitespaceState = whitespaceState
	}

	operator fun String.unaryPlus() {
		content(this@unaryPlus)
	}

	inline operator fun String.invoke(vararg attributes:Pair<String, Any?> = NO_ATTRIBUTES, build:XmlBuilder.() -> Unit) {
		beginTag(this, *attributes)
		build.invoke(this@XmlBuilder)
		endTag()
	}

	companion object {

		private const val WHITESPACE_NONE:Byte = 0
		private const val WHITESPACE_PENDING:Byte = 1
		private const val WHITESPACE_SKIP:Byte = 2

		@PublishedApi
		internal val NO_ATTRIBUTES = emptyArray<Pair<String, Any?>>()

		private fun xmlEscape(c:Char):String? {
			return when (c) {
				'<' -> "&lt;"
				'>' -> "&gt;"
				'\"' -> "&quot;"
				'\'' -> "&#39;" // "&apos;" https://stackoverflow.com/a/17448222
				'&' -> "&amp;"
				else -> null
			}
		}

		operator fun invoke(indent:Boolean = true, build:XmlBuilder.() -> Unit):String {
			val builder = XmlBuilder(indent)
			builder.build()
			return builder.sb.toString()
		}
	}
}