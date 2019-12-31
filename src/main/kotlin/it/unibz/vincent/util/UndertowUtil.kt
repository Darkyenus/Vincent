package it.unibz.vincent.util

import io.undertow.server.DefaultResponseListener
import io.undertow.server.HttpHandler
import io.undertow.server.HttpServerExchange
import io.undertow.server.handlers.form.EagerFormParsingHandler
import io.undertow.server.handlers.form.FormDataParser
import io.undertow.server.handlers.form.FormEncodedDataDefinition
import io.undertow.server.handlers.form.FormParserFactory
import io.undertow.util.Headers
import io.undertow.util.PathTemplateMatch
import io.undertow.util.StatusCodes
import kotlinx.html.HTML
import kotlinx.html.body
import kotlinx.html.h1
import kotlinx.html.head
import kotlinx.html.html
import kotlinx.html.stream.appendHTML
import kotlinx.html.title
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.io.ByteArrayOutputStream
import java.io.OutputStreamWriter
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets

private val LOG: Logger = LoggerFactory.getLogger("UndertowUtil")

// RoutingHandler parameter extractors
/** Retrieve non-empty trimmed string from path parameter with given name.
 * Fails the request if such parameter does not exist.
 *
 * Only usable in [io.undertow.server.HttpHandler] which are used after
 * [io.undertow.server.RoutingHandler] has set path parameters.  */
fun pathString(exchange: HttpServerExchange, name: String): String {
	val attachment = exchange.getAttachment(PathTemplateMatch.ATTACHMENT_KEY)
	if (attachment == null) {
		LOG.error("pathString({}) - no attachment", name)
		throw HttpResponseException(StatusCodes.INTERNAL_SERVER_ERROR, "Failure to parse path")
	}
	return trimToNullAndShorten(attachment.parameters[name], Int.MAX_VALUE)
			?: throw HttpResponseException(StatusCodes.BAD_REQUEST, "Path part '$name' is missing")
}

/** Retrieve integer path parameter.
 * @see pathString */
fun pathInt(exchange: HttpServerExchange, name: String): Int {
	return try {
		pathString(exchange, name).toInt()
	} catch (e: NumberFormatException) {
		throw HttpResponseException(StatusCodes.BAD_REQUEST, "Path part '$name' must be a number")
	}
}

/** Retrieve non-negative integer path parameter.
 * @see pathString */
fun pathIntNonNegative(exchange: HttpServerExchange, name: String): Int {
	val value = pathInt(exchange, name)
	if (value < 0) {
		throw HttpResponseException(StatusCodes.BAD_REQUEST, "Path part '$name' must be a non-negative number")
	}
	return value
}

/** Retrieve non-empty trimmed string from query parameter with given name.
 * Fails the request if such parameter does not exist.
 *
 * Only usable in [io.undertow.server.HttpHandler] which are used after
 * [wrapRootHandler] handler or similar.  */
fun queryString(exchange: HttpServerExchange, name: String, maxSize: Int): String? {
	trimToNullAndShorten(exchange.queryParameters[name]?.firstOrNull(), maxSize)?.let { return it }

	val attachment = exchange.getAttachment(FormDataParser.FORM_DATA)
			?: // The request might not have any and thus there is no content nor content-type
			return null
	val formValues = attachment[name]
	if (formValues == null || formValues.isEmpty()) {
		return null
	}
	for (value in formValues) {
		if (value.isFileItem) {
			continue
		}
		return trimToNullAndShorten(value.value, maxSize) ?: continue
	}
	return null
}

/** Like [.queryString] but for enums.  */
fun <T : Enum<T>?> queryEnum(exchange: HttpServerExchange, name: String, enumClass: Class<T>): T? {
	val s = queryString(exchange, name, Int.MAX_VALUE) ?: return null
	return try {
		java.lang.Enum.valueOf(enumClass, s.toUpperCase())
	} catch (e: IllegalArgumentException) {
		null
	}
}


fun HttpServerExchange.sendHtml(generateHtml: HTML.() -> Unit) {
	val byteOut = object : ByteArrayOutputStream(50_000) {
		fun byteBuffer():ByteBuffer {
			return ByteBuffer.wrap(this.buf, 0, this.count)
		}
	}
	OutputStreamWriter(byteOut, Charsets.UTF_8).use {
		it.append("<!DOCTYPE html>\n")
		it.appendHTML(prettyPrint = false).html {
			generateHtml.invoke(this)
		}
	}

	val byteBuffer = byteOut.byteBuffer()

	val responseHeaders = responseHeaders
	responseHeaders.put(Headers.CONTENT_LENGTH, byteBuffer.remaining().toString())
	responseHeaders.put(Headers.CONTENT_TYPE, "text/html; charset=UTF-8")
	responseSender.send(byteBuffer)
}

/** Wrap [handler] for extra functionality:
 * - make "urlencoded" content accessible
 * - make [HttpResponseException] work
 * - serve nice error pages */
fun wrapRootHandler(handler:HttpHandler): HttpHandler {
	val formParserFactory = FormParserFactory.builder(false)
			.addParser(FormEncodedDataDefinition())
			.withDefaultCharset(StandardCharsets.UTF_8.name())
			.build()
	val formParsingHandler = EagerFormParsingHandler(formParserFactory).apply {
		next = handler
	}

	val defaultHandler = DefaultResponseListener { exchange ->
		if (exchange.isResponseStarted || !exchange.isResponseChannelAvailable) {
			return@DefaultResponseListener false
		}
		val exception = exchange.getAttachment(DefaultResponseListener.EXCEPTION)
		var status = exchange.statusCode
		var message: String? = null
		if (exception is HttpResponseException) {
			status = exception.code
			message = exception.customMessage
		}
		message = message ?: StatusCodes.getReason(status)!!


		exchange.sendHtml {
			head {
				title { +message }
			}
			body {
				h1 { +"$status - $message" }
			}
		}

		true
	}

	return HttpHandler { exchange: HttpServerExchange ->
		exchange.addDefaultResponseListener(defaultHandler)
		formParsingHandler.handleRequest(exchange)
	}
}

/**
 * Thrown by [HttpHandler.handleRequest]
 * and caught by a handler wrapped by [wrapRootHandler] to set content and code
 * to what was thrown.
 *
 * @see StatusCodes
 */
class HttpResponseException(val code: Int,
		val customMessage: String? = StatusCodes.getReason(code)) : Exception()
