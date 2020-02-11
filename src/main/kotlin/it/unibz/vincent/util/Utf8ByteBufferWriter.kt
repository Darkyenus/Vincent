package it.unibz.vincent.util

import java.io.CharArrayWriter
import java.nio.ByteBuffer
import java.nio.CharBuffer

/**
 *
 */
class Utf8ByteBufferWriter : CharArrayWriter(1_000_000) {
	fun utf8Bytes(): ByteBuffer {
		return Charsets.UTF_8.encode(CharBuffer.wrap(buf, 0, count))
	}
}