package it.unibz.vincent.util

import io.undertow.server.HttpServerExchange
import io.undertow.server.handlers.form.MultiPartParserDefinition
import io.undertow.util.HeaderMap
import io.undertow.util.Headers
import io.undertow.util.MalformedMessageException
import io.undertow.util.MultipartParser
import io.undertow.util.MultipartParser.PartHandler
import it.unibz.vincent.util.UploadUtil.MultipartFileHandler
import it.unibz.vincent.util.UploadUtil.MultipartFileHandler.ContinueOrStop
import org.slf4j.LoggerFactory
import org.xnio.channels.StreamSourceChannel
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.WritableByteChannel
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets

private val LOG = LoggerFactory.getLogger("Upload")

/**
 * Utilities & constants related to file uploading.
 *
 * This is needed to prevent files being saved to the disk unnecessarily
 * and to handle both types of upload (direct and multi-part) cleanly.
 */
object UploadUtil {
	private fun readMultipartUploadOf(exchange: HttpServerExchange, boundary: String, charset: String?, uploadedName: String?, dataConsumer: (ByteBuffer) -> ContinueOrStop, onFinish: (ReadMultipartResult) -> Unit) {
		var result = ReadMultipartResult.ERROR_PART_NOT_FOUND
		handleMultipartUpload(exchange, boundary, charset, object : MultipartFileHandler {
			var readingThisFile = false
			override fun beginFile(name: String?, fileName: String?, contentType: String?) {
				if (uploadedName == null || uploadedName.equals(name, ignoreCase = true)) {
					readingThisFile = true
				}
			}

			override fun content(data: ByteBuffer): ContinueOrStop {
				if (readingThisFile) {
					val continueOrStop = dataConsumer(data)
					if (continueOrStop == ContinueOrStop.STOP) {
						result = ReadMultipartResult.CANCELLED
					}
					return continueOrStop
				}
				return ContinueOrStop.CONTINUE
			}

			override fun endFile(error: Boolean): ContinueOrStop {
				if (readingThisFile) {
					if (result != ReadMultipartResult.CANCELLED) {
						result = if (error) {
							ReadMultipartResult.ERROR_OTHER
						} else {
							ReadMultipartResult.SUCCESS
						}
					}
					return ContinueOrStop.STOP
				}
				return ContinueOrStop.CONTINUE
			}
		}) { lowLevelResult ->
			val combinedResult = when {
				result == ReadMultipartResult.SUCCESS -> lowLevelResult
				result == ReadMultipartResult.ERROR_OTHER && lowLevelResult != ReadMultipartResult.SUCCESS -> lowLevelResult
				else -> result
			}
			onFinish(combinedResult)
		}
	}

	/** Read file upload (either direct or multipart with given part name) and write it into the given output channel.
	 * Appropriate HTTP return value is set on the context on failure.
	 * @param multipartName name of the part in multipart request or null to take first part
	 * @param maxSize request is rejected and failed if the file is larger than this many bytes
	 */
	fun readFileUpload(exchange: HttpServerExchange, multipartName: String?, maxSize: Long, out: WritableByteChannel, onFinish: (ReadMultipartResult) -> Unit) {
		val contentType = exchange.requestHeaders.getFirst(Headers.CONTENT_TYPE)
		// Multipart upload
		if (contentType != null && contentType.startsWith(MultiPartParserDefinition.MULTIPART_FORM_DATA)) {
			val boundary = Headers.extractQuotedValueFromHeader(contentType, "boundary")
			if (boundary == null) {
				LOG.debug("Could not find multipart request boundary")
				onFinish(ReadMultipartResult.ERROR_MALFORMED_MULTIPART_MESSAGE)
				return
			}
			val charsetFromHeader = Headers.extractQuotedValueFromHeader(contentType, "charset")
			var totalFileSize = 0L
			readMultipartUploadOf(exchange, boundary, charsetFromHeader, multipartName, { buffer: ByteBuffer ->
				totalFileSize += buffer.remaining()
				if (totalFileSize > maxSize) {
					return@readMultipartUploadOf ContinueOrStop.STOP
				}
				try {
					do {
						out.write(buffer)
					} while (buffer.hasRemaining())
					return@readMultipartUploadOf ContinueOrStop.CONTINUE
				} catch (e: IOException) {
					LOG.warn("readFileUpload: Failed to write to out", e)
					return@readMultipartUploadOf ContinueOrStop.STOP
				}
			}, { result ->
				if (result == ReadMultipartResult.CANCELLED && totalFileSize > maxSize) {
					onFinish(ReadMultipartResult.ERROR_TOO_LARGE)
				} else {
					onFinish(result)
				}
			})
			return
		}
		// Raw upload
		val requestChannel = exchange.requestChannel
		if (requestChannel == null) {
			LOG.error("readFileUpload: request channel already obtained")
			onFinish(ReadMultipartResult.ERROR_OTHER)
			return
		}
		RawUploadAsyncTransfer(exchange, requestChannel, maxSize, out, onFinish).start()
	}

	/** Handles transfer from request channel to the output channel.
	 * Takes care of max length and tries to be as asynchronous as possible.  */
	private class RawUploadAsyncTransfer(
			private val exchange: HttpServerExchange,
			private val requestChannel: StreamSourceChannel,
			private val maxContentLength: Long,
			private val out: WritableByteChannel,
			private val onFinish: (ReadMultipartResult) -> Unit) : Runnable {

		private val expectedContentLength: Long
		private var done = false
		private var currentContentLength: Long = 0
		/** Start the copying of request data into the output channel.  */
		fun start() {
			if (expectedContentLength > maxContentLength) {
				done = true
				onFinish(ReadMultipartResult.ERROR_TOO_LARGE)
				return
			}
			// Since output channel might be blocking, run on background thread
			if (exchange.isInIoThread) {
				exchange.dispatch(this)
			} else {
				run()
			}
		}

		/** Wraps the actual processing to ensure that onDone is called exactly once.  */
		override fun run() {
			if (done) {
				LOG.warn("run() called after done")
				return
			}
			val result = process() ?: return
			done = true
			onFinish(result)
		}

		private fun process(): ReadMultipartResult? {
			val exchange = exchange
			val requestChannel = requestChannel
			try {
				exchange.connection.byteBufferPool.allocate().use { pooled ->
					val buffer = pooled.buffer
					buffer.clear()
					while (true) {
						val read = requestChannel.read(buffer)
						if (read == 0) {
							requestChannel.readSetter.set { channel: StreamSourceChannel ->
								channel.suspendReads()
								exchange.dispatch(this@RawUploadAsyncTransfer)
							}
							requestChannel.resumeReads()
							return null
						} else if (read == -1) { // Done
							if (expectedContentLength > 0 && currentContentLength != expectedContentLength) { // Invalid size!
								return ReadMultipartResult.ERROR_MALFORMED_RAW_UPLOAD
							}
							return ReadMultipartResult.SUCCESS
						}
						currentContentLength += read.toLong()
						@Suppress("ConvertTwoComparisonsToRangeCheck")
						if (expectedContentLength > 0 && currentContentLength > expectedContentLength) { // Got too much!
							return ReadMultipartResult.ERROR_MALFORMED_RAW_UPLOAD
						}
						if (currentContentLength > maxContentLength) {
							return ReadMultipartResult.ERROR_TOO_LARGE
						}
						buffer.flip()
						try {
							do {
								out.write(buffer)
							} while (buffer.hasRemaining())
							buffer.clear()
						} catch (e: IOException) {
							LOG.debug("Failed to write to out", e)
							return ReadMultipartResult.ERROR_OTHER
						}
					}
				}
				// Code should not ever get here - Kotlin thinks that it could because it doesn't understand how `use` works.
				throw AssertionError()
			} catch (e: Exception) {
				LOG.debug("Failed to handle upload", e)
				return ReadMultipartResult.ERROR_OTHER
			}
		}

		init {
			val contentLengthStr = exchange.requestHeaders.getFirst(Headers.CONTENT_LENGTH)
			var contentLength: Long = -1
			if (contentLengthStr != null) {
				try {
					contentLength = contentLengthStr.toLong()
				} catch (ignored: NumberFormatException) {
				}
			}
			expectedContentLength = contentLength
		}
	}

	interface MultipartFileHandler {
		/** First call to begin handling a file.  */
		fun beginFile(name: String?, fileName: String?, contentType: String?)

		/** Second call, contains data to handle as a part of the started file.
		 * May be called repeatedly or not at all. Unconsumed data will be discarded.
		 * Returning [ContinueOrStop.STOP] will still cause [.endFile] to be called with
		 * `false` (error) argument.  */
		fun content(data: ByteBuffer): ContinueOrStop

		/** Called after [.beginFile] and zero or more [.content],
		 * notifies that the file is no longer being processed. If this is due to an error, error is set to true,
		 * and the content is to be considered incomplete.
		 * After this, beginFile may be called again.  */
		fun endFile(error: Boolean): ContinueOrStop

		enum class ContinueOrStop {
			/** Continue reading normally.  */
			CONTINUE,
			/** Stop reading.  */
			STOP
		}
	}
}

enum class ReadMultipartResult {
	SUCCESS,
	CANCELLED,
	ERROR_TOO_LARGE,
	ERROR_PART_NOT_FOUND,
	ERROR_MALFORMED_MULTIPART_MESSAGE,
	ERROR_MALFORMED_RAW_UPLOAD,
	ERROR_OTHER
}

/**
 * Read a multipart upload that is waiting on the given `exchange`.
 * @param boundary the multipart boundary, parsed from Content-Type header
 * @param charset default charset, if specified in Content-Type header
 * @param handler that will be called on each encountered part and data
 * @param finishedCallback to be called when the processing is done
 */
fun handleMultipartUpload(exchange: HttpServerExchange, boundary: String, charset: String?, handler: MultipartFileHandler, finishedCallback: (ReadMultipartResult) -> Unit) {
	val requestChannel = exchange.requestChannel
			?: throw RuntimeException("handleMultipartUpload: Request channel already provided")
	val upload = MultiPartUpload(exchange, boundary, charset, handler, requestChannel, finishedCallback)
	upload.run()
}

/**
 * Internal utility class to handle multipart uploads asynchronously without unnecessary IO to disk, as the default
 * Undertow handler does.
 * <br></br>
 * Not to be instantiated directly.
 * <br></br>
 * Core logic is based on [io.undertow.server.handlers.form.MultiPartParserDefinition]`.MultiPartUploadHandler`,
 * which is under the Apache License.
 * <br></br>
 * See [MDN](https://developer.mozilla.org/en-US/docs/Web/HTTP/Headers/Content-Type) for info about multipart
 * uploads.
 *
 * @see handleMultipartUpload
 */
private class MultiPartUpload(
		private val exchange: HttpServerExchange,
		/** The multipart boundary, parsed from Content-Type header */
		boundary: String,
		/** Default charset, if specified in Content-Type header */
		charset: String?,
		/** Our own callback that processes the read data and possibly determines when to stop reading.  */
		private val handler: MultipartFileHandler,
		/** The channel with the request body. (Cached, because it can be retrieved only once.)  */
		private val requestChannel: StreamSourceChannel,
		/** To be called after the parsing has finished, either on error or successfully.  */
		private val finishedCallback: (ReadMultipartResult) -> Unit) : PartHandler, Runnable {

	/** Handles actual multipart body parsing  */
	private val parser: MultipartParser.ParseState = MultipartParser.beginParse(
			exchange.connection.byteBufferPool,
			this, boundary.toByteArray(StandardCharsets.US_ASCII),
			// Prevent problems down the line and refuse to use unsupported charsets
			if (charset == null || !Charset.isSupported(charset)) Charsets.UTF_8.name() else charset)

	/** `handler` can cancel further processing, which is then recorded here.  */
	private var continueParsing = true
	/** To ensure that we always call [UploadUtil.MultipartFileHandler.endFile] after
	 * [UploadUtil.MultipartFileHandler.beginFile], even on error.  */
	private var partStarted = false

	/** Called when [MultipartParser] encounters a new part beginning.  */
	override fun beginPart(headers: HeaderMap) {
		assert(!partStarted)
		var name: String? = null
		var filename: String? = null
		val disposition = headers.getFirst(Headers.CONTENT_DISPOSITION)
		if (disposition != null && disposition.startsWith("form-data")) {
			name = Headers.extractQuotedValueFromHeader(disposition, "name")
			filename = Headers.extractQuotedValueFromHeaderWithEncoding(disposition, "filename")
		}
		val contentType = headers.getFirst(Headers.CONTENT_TYPE)
		partStarted = true
		handler.beginFile(name, filename, contentType)
	}

	/** Called when [MultipartParser] encounters a new part data.  */
	override fun data(buffer: ByteBuffer) {
		assert(partStarted)
		continueParsing = handler.content(buffer) == ContinueOrStop.CONTINUE
	}

	/** Called when [MultipartParser] encounters the end of the current part.  */
	override fun endPart() {
		assert(partStarted)
		partStarted = false
		continueParsing = handler.endFile(false) == ContinueOrStop.CONTINUE
	}

	/** Called repeatedly by connection executor when there is more data to be read (or for initial reading).  */
	override fun run() {
		val requestChannel = requestChannel
		val exchange = exchange
		val parser = parser
		// To detect if the loop has ended successfully or because of an error
		var result = ReadMultipartResult.SUCCESS
		try {
			exchange.connection.byteBufferPool.allocate().use { pooled ->
				val buffer = pooled.buffer
				buffer.clear()
				while (true) {
					val read = requestChannel.read(buffer)
					if (read == 0) { // Not enough data, but more is on the way
						requestChannel.readSetter.set { channel: StreamSourceChannel ->
							channel.suspendReads()
							exchange.dispatch(this@MultiPartUpload)
						}
						requestChannel.resumeReads()
						return  // We will get notified after there is more data to read
					} else if (read < 0) { // Done reading, end of request
						if (!parser.isComplete) {
							// We wanted more
							result = ReadMultipartResult.ERROR_MALFORMED_MULTIPART_MESSAGE
						}
						break // EXIT LOOP
					}
					// Got more data, parse it
					buffer.flip()
					parser.parse(buffer)
					if (!continueParsing) { // Stop parsing then
						break // EXIT LOOP
					}
					buffer.compact()
				}
			}
		} catch (e: MalformedMessageException) {
			LOG.debug("Failed to parse multipart message", e)
			result = ReadMultipartResult.ERROR_MALFORMED_MULTIPART_MESSAGE
		} catch (e: Exception) {
			LOG.debug("Failed to read multipart message", e)
			result = ReadMultipartResult.ERROR_OTHER
		}
		// This is the end of any parsing. If a part is still open, we have encountered an error, and we should notify the handler about it.
		if (partStarted) {
			assert(result != ReadMultipartResult.SUCCESS)
			handler.endFile(true)
			partStarted = false
		}
		exchange.dispatch(Runnable { finishedCallback(result) })
	}
}